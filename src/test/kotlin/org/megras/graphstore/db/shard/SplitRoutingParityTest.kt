package org.megras.graphstore.db.shard

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.megras.data.graph.DoubleValue
import org.megras.data.graph.FloatVectorValue
import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.data.graph.StringValue
import org.megras.data.graph.VectorValue
import org.megras.graphstore.Distance
import org.megras.graphstore.QuadSet
import org.megras.graphstore.db.ClusterQuadSet
import org.megras.graphstore.db.dict.PostgresDictionary
import java.net.InetSocketAddress
import java.net.Socket
import java.sql.DriverManager

/**
 * Stage-D rig: the FIRST test to exercise a non-trivial [SplitShardPolicy].
 * Validates that split routing + the partitioned value-level fuse + the
 * distance-bearing NN merge preserve correctness against a pure-Kotlin
 * ground-truth oracle -- NOT against another implementation. The earlier
 * cluster tests only cover [TrivialShardPolicy]; they cannot detect a bug
 * that manifests only when scalar rows and vector content are spread across
 * distinct shards (e.g. a vector id broadcast letting a foreign shard match a
 * colliding discriminator, or a per-shard NN top-k losing a global candidate).
 *
 * Why a math oracle, not a trivial-leg comparison:
 * - Exact-match filters (filter/filterSubject/filterPredicate/filterObject/
 *   distinctObjects/distinctSubjects) have a trivial closed-form oracle: the
 *   set of corpus quads matching the conjunctive wildcard pattern. No DB
 *   semantics involved.
 * - nearestNeighbor over a tiny corpus with distinct cosine similarities has a
 *   trivial closed-form oracle: rank corpus vectors by cosine sim and take the
 *   top-k. pgvector's approximate index is bypassed by brute-force scan at
 *   this scale (the corpus has 3 vectors), so implementation ordering cannot
 *   diverge; tie-breaking is moot because the query/corpus similarities are
 *   distinct by construction.
 * Comparing to ground truth is STRICTER than comparing two implementations:
 * a shared bug in both substrate legs would pass a parity test but fail here.
 *
 * textFilter is intentionally NOT exercised here. Its oracle would have to
 * reproduce PostgreSQL 'english' tsvector stemming, which is fragile and
 * orthogonal to routing. It is already covered end-to-end against the
 * substrate by ClusterPathExpectationTest; the split-policy risk it would
 * expose (cross-shard scalar alias fusion) is already exercised by the
 * exact-match + distinct batteries below.
 *
 * Distributed fidelity: each shard endpoint AND the dict endpoint MUST live on
 * a distinct server (separate containers in practice), because both
 * PostgresShard and PostgresDictionary hardcode the schema name `megras` --
 * colocation on one DB would alias their tables. The rig self-skips unless the
 * env vars name reachable endpoints (MEGRAS_SPLIT_DICT host:port, and
 * MEGRAS_SPLIT_SHARDS comma-list of host:port with >= 2 entries), so
 * `./gradlew test` stays container-free. The reset drops the `megras` schema
 * CASCADE on every endpoint before each run -- the rig assumes exclusive
 * ownership of disposable containers.
 *
 * add contract: returns true iff the quad was newly stored, false iff it
 * already existed (see ClusterQuadSet.add -> routeAdd.quadId != null). The
 * unique-add / repeat-add assertions check that contract holds under split
 * placement, including for shared vector operands routed off the subject's
 * hash shard (the getOrAdd co-location case).
 */
class SplitRoutingParityTest {

    /** Reachable host:port list for split SHARDS (>=2), or null to self-skip. */
    private val splitShardHosts: List<String>? =
        System.getenv("MEGRAS_SPLIT_SHARDS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
    /** Reachable host:port for the GLOBAL dict, or null to self-skip. */
    private val splitDictHost: String? = System.getenv("MEGRAS_SPLIT_DICT")?.trim()?.ifBlank { null }

    private val user = "megras"
    private val pass = "megras"

    /** Endpoints are held as bare host:port (for the socket gate); every JDBC
     *  path -- the reset and the store/dict ctors -- requires the /megras db
     *  segment that the ctors' own defaults carry. Centralize the rewrite. */
    private fun dbForm(endpoint: String): String = "$endpoint/megras"

    private fun reachable(hostPort: String): Boolean = try {
        val (h, p) = hostPort.split(':').let { it[0] to it[1].toInt() }
        Socket().use { it.connect(InetSocketAddress(h, p), 200); true }
    } catch (e: Exception) {
        false
    }

    private fun gate(): Boolean {
        val ds = splitDictHost
        val ss = splitShardHosts
        if (ds == null || ss == null || ss.size < 2) return false
        return reachable(ds) && ss.all { reachable(it) }
    }

    private fun reset(endpoint: String) {
        DriverManager.getConnection("jdbc:postgresql://${dbForm(endpoint)}", user, pass).use { c ->
            c.createStatement().use { it.execute("DROP SCHEMA IF EXISTS megras CASCADE;") }
        }
    }

    private fun clusterStore(): ClusterQuadSet {
        val dict = PostgresDictionary(dbForm(splitDictHost!!), user, pass)
        val shards = splitShardHosts!!.map { PostgresShard(dbForm(it), user, pass) }
        return ClusterQuadSet(dict, SplitShardPolicy(shards)).also {
            dict.setup()
            shards.forEach { it.setup() }
        }
    }

    // ---- fixed corpus ------------------------------------------------------

    private val s1 = QuadValue.of("http://ex/s1")
    private val s2 = QuadValue.of("http://ex/s2")
    private val sl = QuadValue.of("http://localhost/l1")
    private val p1 = QuadValue.of("http://ex/p1")
    private val p2 = QuadValue.of("http://ex/p2")
    private val pv = QuadValue.of("http://ex/pv")
    private val ps = QuadValue.of("http://ex/ps")
    private val ou1 = QuadValue.of("http://ex/o1")
    private val ou2 = QuadValue.of("http://ex/o2")
    private val ou3 = QuadValue.of("http://ex/o3")
    private val doub = DoubleValue(1.5)
    private val strx = StringValue("str-x")
    private val stra = StringValue("alpha bravo")
    private val strb = StringValue("bravo charlie")
    private val vec1 = QuadValue.of(floatArrayOf(1f, 0f, 0f))
    private val vec2 = QuadValue.of(floatArrayOf(0f, 1f, 0f))
    private val queryVec = QuadValue.of(floatArrayOf(0.9f, 0.1f, 0f)) as VectorValue

    private val corpus: List<Quad> = listOf(
        Quad(null, s1, p1, ou1),
        Quad(null, s1, p2, ou2),
        Quad(null, s2, p1, ou3),
        Quad(null, s2, p2, doub),
        Quad(null, sl, p1, strx),
        Quad(null, s1, ps, stra),
        Quad(null, s2, ps, strb),
        Quad(null, s1, pv, vec1),
        Quad(null, s2, pv, vec1),
        Quad(null, s1, pv, vec2),
    )

    // ---- ground-truth oracle ----------------------------------------------
    // Closed-form on the in-memory corpus. QuadValue equality is value-based,
    // so comparing oracle output to normalized DB output is sound across
    // independent id spaces (dict ids are autoincrement, NOT content-stable,
    // but every comparison is on values, never ids).

    private fun Triple<QuadValue, QuadValue, QuadValue>.matches(
        s: Set<QuadValue>?, p: Set<QuadValue>?, o: Set<QuadValue>?
    ): Boolean = (s == null || this.first in s) &&
        (p == null || this.second in p) &&
        (o == null || this.third in o)

    private fun oracleFilter(
        s: Set<QuadValue>?, p: Set<QuadValue>?, o: Set<QuadValue>?
    ): Set<Triple<QuadValue, QuadValue, QuadValue>> {
        val out = HashSet<Triple<QuadValue, QuadValue, QuadValue>>()
        for (q in corpus) {
            val t = Triple(q.subject, q.predicate, q.`object`)
            if (t.matches(s, p, o)) out.add(t)
        }
        return out
    }

    private fun oracleDistinctObjects(pred: QuadValue): Set<QuadValue> =
        corpus.asSequence().filter { it.predicate == pred }.map { it.`object` }.toSet()

    private fun oracleDistinctSubjects(pred: QuadValue): Set<QuadValue> =
        corpus.asSequence().filter { it.predicate == pred }.map { it.subject }.toSet()

    private fun cosineSim(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i].toDouble()
            na += a[i].toDouble() * a[i].toDouble()
            nb += b[i].toDouble() * b[i].toDouble()
        }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / (Math.sqrt(na) * Math.sqrt(nb))
    }

    /**
     * Oracle NN: rank corpus vectors sharing (pred) by cosine sim to [query]
     * and keep the top-k (highest sim, normal) or bottom-k (lowest sim, invert).
     * Returns the (subject, pred, vector) triples for the survivors -- the same
     * projection the substrate reverse-resolves. Refuses to rank a vector of
     * differing type/length (would never be a legal NN candidate under the
     * substrate's typed indexes either).
     */
    private fun oracleNN(
        pred: QuadValue, query: VectorValue, count: Int, distance: Distance, invert: Boolean
    ): Set<Triple<QuadValue, QuadValue, VectorValue>> {
        if (distance != Distance.COSINE) return emptySet()
        val qb = (query as? FloatVectorValue)?.vector ?: return emptySet()
        val ranked = ArrayList<Pair<Double, VectorValue>>()
        for (q in corpus) {
            if (q.predicate != pred) continue
            val ov = q.`object` as? FloatVectorValue ?: continue
            if (ov.length != query.length || ov.type != query.type) continue
            ranked.add(cosineSim(qb, ov.vector) to ov)
        }
        if (ranked.isEmpty()) return emptySet()
        val selected = if (invert) ranked.sortedBy { it.first }.take(count)
                       else ranked.sortedByDescending { it.first }.take(count)
        val out = HashSet<Triple<QuadValue, QuadValue, VectorValue>>()
        for ((_, ov) in selected) {
            // Resolve back to the subject(s) bearing (pred, ov) -- a vector
            // object is shared across subjects in this corpus, so mirror that.
            for (q in corpus) {
                if (q.predicate == pred && q.`object` == ov) {
                    out.add(Triple(q.subject, q.predicate, ov))
                }
            }
        }
        return out
    }

    // ---- normalization -----------------------------------------------------

    private fun norm(qs: QuadSet): Set<Triple<QuadValue, QuadValue, QuadValue>> =
        qs.map { Triple(it.subject, it.predicate, it.`object`) }.toSet()

    private fun normVec(qs: QuadSet): Set<Triple<QuadValue, QuadValue, VectorValue>> =
        qs.map { Triple(it.subject, it.predicate, it.`object` as VectorValue) }.toSet()

    // ---- the rig -----------------------------------------------------------

    @BeforeEach
    fun prepare() {
        assumeTrue(gate(), "split rig endpoints not configured/unreachable (set MEGRAS_SPLIT_DICT + MEGRAS_SPLIT_SHARDS)")
        // Exclusive disposal: wipe every endpoint's schema before each test so
        // prior corpus revisions or aborted runs cannot leak into results.
        reset(splitDictHost!!)
        splitShardHosts!!.forEach { reset(it) }
    }

    @Test
    fun addContractHoldsUnderSplitPlacement() {
        val store = clusterStore()
        for (q in corpus) assertTrue(store.add(q), "unique add returned false: $q")
        assertFalse(store.add(corpus.first()), "repeat add returned true (should be a no-op)")
    }

    @Test
    fun exactFiltersMatchGroundTruth() {
        val store = clusterStore()
        corpus.forEach { store.add(it) }
        assertEquals(oracleFilter(setOf(s1), null, null), norm(store.filterSubject(s1)))
        assertEquals(oracleFilter(setOf(s2), null, null), norm(store.filterSubject(s2)))
        assertEquals(oracleFilter(setOf(sl), null, null), norm(store.filterSubject(sl)))
        assertEquals(oracleFilter(null, setOf(p1), null), norm(store.filterPredicate(p1)))
        assertEquals(oracleFilter(null, setOf(p2), null), norm(store.filterPredicate(p2)))
        assertEquals(oracleFilter(null, setOf(pv), null), norm(store.filterPredicate(pv)))
        assertEquals(oracleFilter(null, setOf(ps), null), norm(store.filterPredicate(ps)))
        assertEquals(oracleFilter(null, null, setOf(ou1)), norm(store.filterObject(ou1)))
        assertEquals(oracleFilter(null, null, setOf(vec1)), norm(store.filterObject(vec1)))
        assertEquals(oracleFilter(setOf(s1, s2), null, null), norm(store.filter(setOf(s1, s2), null, null)))
        assertEquals(oracleFilter(null, setOf(p1, p2), null), norm(store.filter(null, setOf(p1, p2), null)))
        assertEquals(oracleFilter(null, null, setOf(ou1, ou2)), norm(store.filter(null, null, setOf(ou1, ou2))))
        assertEquals(oracleFilter(setOf(s1), setOf(p1), null), norm(store.filter(setOf(s1), setOf(p1), null)))
        // vector-object conjunction: routes the vector operand only to its
        // content shard -- the case partitionOperand exists to protect.
        assertEquals(oracleFilter(null, setOf(pv), setOf(vec1)), norm(store.filter(null, setOf(pv), setOf(vec1))))
    }

    @Test
    fun distinctMatchesGroundTruth() {
        val store = clusterStore()
        corpus.forEach { store.add(it) }
        assertEquals(oracleDistinctObjects(p1), store.distinctObjects(p1))
        assertEquals(oracleDistinctObjects(ps), store.distinctObjects(ps))
        assertEquals(oracleDistinctObjects(pv), store.distinctObjects(pv))
        assertEquals(oracleDistinctSubjects(p1), store.distinctSubjects(p1))
    }

    @Test
    fun nearestNeighborGlobalTopKAcrossShards() {
        val store = clusterStore()
        corpus.forEach { store.add(it) }
        // k >= corpus size: the merge must return the entire corpus slice.
        assertEquals(oracleNN(pv, queryVec, 100, Distance.COSINE, false), normVec(store.nearestNeighbor(pv, queryVec, 100, Distance.COSINE, false)))
        // k < corpus: selection (NOT ordering) must be the global top-k,
        // forcing the distance-bearing merge to beat every per-shard local
        // top-k. This is the core stage-B/C invariant: vec1 and vec2 live on
        // DISTINCT shards, so a correct global top-2 (both vec1 rows) must
        // disqualify vec2 offered by its own shard.
        assertEquals(oracleNN(pv, queryVec, 2, Distance.COSINE, false), normVec(store.nearestNeighbor(pv, queryVec, 2, Distance.COSINE, false)))
        assertEquals(oracleNN(pv, queryVec, 1, Distance.COSINE, true), normVec(store.nearestNeighbor(pv, queryVec, 1, Distance.COSINE, true)))
        // NOTE: k=1 (normal) and k=2 (invert) are deliberately OMITTED. The
        // corpus shares vec1 across two subjects, so the NN query sees TWO rows
        // at the same distance. The substrate returns k ROWS (it joins quads to
        // the vector table and LIMITs, NOT k distinct vectors projected to all
        // subjects -- that is the direct PostgresStore contract the existing
        // parity test already pins). At k=1-normal / k=2-invert the k-th
        // boundary falls BETWEEN tied rows, so which subject survives is
        // tie-break-dependent and has no deterministic oracle -- the same
        // limitation ClusterParityTest documents. Boundary-safe k only.
    }
}
