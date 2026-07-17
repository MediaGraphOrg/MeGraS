package org.megras.graphstore.db.shard

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.data.graph.VectorValue
import org.megras.graphstore.Distance
import org.megras.graphstore.MutableQuadSet
import org.megras.graphstore.db.ClusterQuadSet
import org.megras.graphstore.db.PostgresStore
import org.megras.graphstore.db.dict.PostgresDictionary
import java.net.InetSocketAddress
import java.net.Socket
import java.sql.DriverManager

/**
 * Direct-vs-cluster parity: the cluster substrate (ClusterQuadSet over a
 * TrivialShardPolicy + PostgresShard, scalar ids from PostgresDictionary)
 * must produce the same observable results as the single-node PostgresStore
 * for a fixed, realistic corpus (one that shares scalar and vector terms
 * across quads — the case that forced the getOrAdd fix on the add path).
 *
 * Runs SEQUENTIALLY on one disposable PG: the megras schema is dropped
 * CASCADE, leg A (direct PostgresStore) records a normalized result battery,
 * the schema is dropped CASCADE again, leg B (cluster) re-initializes and
 * records the same battery; the two recordings are asserted equal. One DB
 * suffices because the two legs never coexist — both declare
 * `quads`/`vector_types`, so they cannot share a schema simultaneously. The
 * leading reset is not optional: without it leg A reads whatever state a prior
 * run (possibly against a different corpus revision) left on the persistent
 * container, and the add booleans / read sets diverge from leg B's clean start
 * — a harness artifact, not a substrate divergence.
 *
 * Self-skips unless localhost:55433 is listening, so `./gradlew test` stays
 * PG-free. The reset leaks leg A's connection pools (no close API); acceptable
 * against a disposable container, not pretty.
 *
 * Normalization: QuadSet results collapse to Set<Triple<s,p,o>> ignoring the
 * storage row id (direct path sets a row id, cluster sets null — both are
 * internal pointers, not semantic ids; see the quad-semantic-id-redesign
 * notes). QuadValue equality is value-based across the hierarchy (URI by
 * prefix/suffix, vector by content), so this comparison is stable across
 * independent DB instances. NN is compared as an unordered set with k larger
 * than the corpus, so approximate-index ordering cannot diverge.
 */
class ClusterParityTest {

    private val conn = "localhost:55433/megras"
    private val user = "megras"
    private val pass = "megras"

    private fun pgAvailable(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("localhost", 55433), 200); true }
    } catch (e: Exception) {
        false
    }

    private fun resetSchema() {
        DriverManager.getConnection("jdbc:postgresql://$conn", user, pass).use { c ->
            c.createStatement().use { it.execute("DROP SCHEMA IF EXISTS megras CASCADE;") }
        }
    }

    private fun directStore(): PostgresStore {
        val dict = PostgresDictionary(conn, user, pass)
        val store = PostgresStore(dict, conn, user, pass)
        dict.setup(); store.setup()
        return store
    }

    private fun clusterStore(): ClusterQuadSet {
        val dict = PostgresDictionary(conn, user, pass)
        val shard = PostgresShard(conn, user, pass)
        return ClusterQuadSet(dict, TrivialShardPolicy(shard)).also {
            dict.setup(); shard.setup()
        }
    }

    // Fixed corpus: shares subject s1 across predicates, shares predicate p1
    // across subjects, shares vector object vec1 across two subjects, mixes
    // scalar object types. No duplicate (s,p,o) triples. Exercises the add
    // path's getOrAdd for every shared-term category.
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
    private val vec1 = QuadValue.of(floatArrayOf(1f, 0f, 0f))
    private val vec2 = QuadValue.of(floatArrayOf(0f, 1f, 0f))
    private val queryVec = QuadValue.of(floatArrayOf(0.9f, 0.1f, 0f)) as VectorValue

    private val corpus: List<Quad> = listOf(
        Quad(null, s1, p1, ou1),
        Quad(null, s1, p2, ou2),
        Quad(null, s2, p1, ou3),
        Quad(null, s2, p2, org.megras.data.graph.DoubleValue(1.5)),
        Quad(null, sl, p1, org.megras.data.graph.StringValue("str-x")),
        Quad(null, s1, ps, org.megras.data.graph.StringValue("alpha bravo")),
        Quad(null, s2, ps, org.megras.data.graph.StringValue("bravo charlie")),
        Quad(null, s1, pv, vec1),
        Quad(null, s2, pv, vec1),
        Quad(null, s1, pv, vec2),
    )

    private fun norm(qs: org.megras.graphstore.QuadSet): Set<Triple<QuadValue, QuadValue, QuadValue>> =
        qs.map { Triple(it.subject, it.predicate, it.`object`) }.toSet()

    /**
     * Records the add-results + read-battery for one store. The add booleans
     * and every normalized read result land in one flat list so the two legs'
     * recordings are a single equality check.
     */
    private fun battery(store: MutableQuadSet): List<Any> {
        val out = ArrayList<Any>()
        for (q in corpus) out.add(store.add(q))
        out.add(norm(store.filterSubject(s1)))
        out.add(norm(store.filterSubject(s2)))
        out.add(norm(store.filterSubject(sl)))
        out.add(norm(store.filterPredicate(p1)))
        out.add(norm(store.filterPredicate(p2)))
        out.add(norm(store.filterPredicate(pv)))
        out.add(norm(store.filterPredicate(ps)))
        out.add(norm(store.filterObject(ou1)))
        out.add(norm(store.filterObject(vec1)))
        out.add(norm(store.filter(setOf(s1, s2), null, null)))
        out.add(norm(store.filter(null, setOf(p1, p2), null)))
        out.add(norm(store.filter(null, null, setOf(ou1, ou2))))
        out.add(norm(store.filter(setOf(s1), setOf(p1), null)))
        out.add(norm(store.filter(null, setOf(pv), setOf(vec1))))
        out.add(norm(store.nearestNeighbor(pv, queryVec, 100, Distance.COSINE)))
        // k<corpus exercises the distance-bearing top-k merge's SELECTION (not
        // ordering — QuadSet is unordered). Single-shard, the merge collapses
        // to the direct path; this guards against future regressions in the
        // merge. Multi-shard selection is validated only in the stage-D rig.
        out.add(norm(store.nearestNeighbor(pv, queryVec, 1, Distance.COSINE)))
        out.add(norm(store.nearestNeighbor(pv, queryVec, 2, Distance.COSINE)))
        out.add(norm(store.textFilter(ps, "bravo")))
        out.add(store.distinctObjects(p1))
        out.add(store.distinctObjects(ps))
        out.add(store.distinctObjects(pv))
        out.add(store.distinctSubjects(p1))
        return out
    }

    @Test
    fun directVsClusterParity() {
        assumeTrue(pgAvailable(), "disposable PG not listening on localhost:55433")
        resetSchema()
        val a = battery(directStore())
        resetSchema()
        val b = battery(clusterStore())
        assertEquals(a, b, "direct PostgresStore and cluster substrate diverged")
    }
}
