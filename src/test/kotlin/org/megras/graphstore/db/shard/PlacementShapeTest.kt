package org.megras.graphstore.db.shard

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.graphstore.db.ClusterQuadSet
import org.megras.graphstore.db.dict.PostgresDictionary
import java.net.InetSocketAddress
import java.net.Socket
import java.sql.DriverManager

/**
 * Placement-shape rig: the ONLY test that distinguishes the distribution
 * policies under broadcast reads. [SplitRoutingParityTest] is
 * routing-invariant -- it passes identically for every policy because reads
 * broadcast and correctness does not depend on where a row lands. This rig
 * observes placement directly by counting rows per shard via raw JDBC
 * (`SELECT COUNT(*) FROM megras.quads`), bypassing the QuadSet seam.
 *
 * What is observable: TOTAL rows per shard only. Subjects are stored as
 * shard-local ids, so per-subject placement is not recoverable from the
 * quads table without the dict; the test reads aggregate counts and reasons
 * about distribution SHAPE.
 *
 * Assertions, by policy:
 * - TRIVIAL: every row on the single shard (shard 0). Degenerate baseline.
 * - SPLIT / ROUND_ROBIN / QUAD_HASH: rows spread approximately uniformly
 *   across shards (loose band; no shard dominates). These three are NOT
 *   distinguished from each other by row counts -- all uniform -- telling
 *   them apart needs per-subject or placement-ORDER instrumentation (RR
 *   cycles, which shard hosts which subject), deliberately out of scope.
 * - PREFIX: with a corpus of many prefixes each contributing M
 *   identical-cardinality subjects, per-shard totals must be multiples of M
 *   (each prefix contributes 0 or M to a shard -- per-prefix concentration)
 *   and must populate more than one shard. The multiples-of-M property is
 *   the positive prefix signature: uniform policies produce continuous
 *   spreads that are not multiples of M. Using MANY prefixes (not a
 *   spanning probe) makes all-on-one-shard vanishingly unlikely without
 *   choosing prefixes by their routing outcome (no fishing).
 *
 * Distributed fidelity + self-skip: same env knobs as the split rig
 * (MEGRAS_SPLIT_DICT + MEGRAS_SPLIT_SHARDS >= 2, distinct servers). Resets
 * every endpoint's `megras` schema CASCADE before each test.
 *
 * Out of scope: per-subject placement, RR cycle order, vector placement
 * (shared value-hash), and RR broadcast dup-check correctness (implicit in
 * counts not inflating; a direct re-add assertion belongs in the
 * correctness rig if extended).
 */
class PlacementShapeTest {

    private val splitShardHosts: List<String>? =
        System.getenv("MEGRAS_SPLIT_SHARDS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
    private val splitDictHost: String? = System.getenv("MEGRAS_SPLIT_DICT")?.trim()?.ifBlank { null }
    private val user = "megras"
    private val pass = "megras"

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

    /**
     * Builds the shards ONCE and constructs the policy on that SAME list via
     * [factory], so setup initializes exactly the shards the policy holds --
     * dual instantiation would no-op setup on the policy's shards. The
     * factory pattern keeps the test sites from repeating shard construction.
     */
    private fun multiShardStore(factory: (List<Shard>) -> ShardPolicy): ClusterQuadSet {
        val dict = PostgresDictionary(dbForm(splitDictHost!!), user, pass)
        val shards = splitShardHosts!!.map { PostgresShard(dbForm(it), user, pass) }
        return ClusterQuadSet(dict, factory(shards)).also {
            dict.setup()
            shards.forEach { it.setup() }
        }
    }

    private fun trivialStore(): ClusterQuadSet {
        val dict = PostgresDictionary(dbForm(splitDictHost!!), user, pass)
        val shard = PostgresShard(dbForm(splitShardHosts!![0]), user, pass)
        return ClusterQuadSet(dict, TrivialShardPolicy(shard)).also {
            dict.setup(); shard.setup()
        }
    }

    /** Raw per-shard row count, in [splitShardHosts] order. Every endpoint
     *  is assumed setup (multi-shard tests setup all); the trivial test uses
     *  [countAt] on its single endpoint instead, since it sets up only that
     *  one and querying the others would hit an absent schema. */
    private fun rowCounts(): List<Long> = splitShardHosts!!.map { countAt(it) }

    private fun countAt(ep: String): Long =
        DriverManager.getConnection("jdbc:postgresql://${dbForm(ep)}", user, pass).use { c ->
            c.createStatement().use { rs ->
                rs.executeQuery("SELECT COUNT(*) FROM megras.quads;").use { it.next(); it.getLong(1) }
            }
        }

    private val pred = QuadValue.of("http://ex/p1")
    private val obj = QuadValue.of("http://ex/o1")
    private val uniformN = 4000
    private val prefixM = 600
    private val prefixCount = 20 // many prefixes -> all-on-one-shard negligibly likely
    private val uniformMaxFraction = 0.60
    private val uniformMinFraction = 0.40

    private fun uniformCorpus(n: Int): List<Quad> =
        (0 until n).map { Quad(null, QuadValue.of("http://ex/$it"), pred, obj) }

    private fun prefixCorpus(m: Int): List<Quad> =
        (0 until prefixCount).flatMap { p ->
            (0 until m).map { s -> Quad(null, QuadValue.of("http://host$p/x$s"), pred, obj) }
        }

    @BeforeEach
    fun prepare() {
        assumeTrue(gate(), "split rig endpoints not configured/unreachable (set MEGRAS_SPLIT_DICT + MEGRAS_SPLIT_SHARDS)")
        reset(splitDictHost!!)
        splitShardHosts!!.forEach { reset(it) }
    }

    private fun assertUniformSpread(counts: List<Long>) {
        val total = counts.sum()
        assertTrue(total > 0, "no rows placed; corpus did not land")
        for (c in counts) {
            val frac = c.toDouble() / total
            assertTrue(frac < uniformMaxFraction && frac > uniformMinFraction,
                "uniform-spread violation: counts=$counts fraction=$frac outside ($uniformMinFraction,$uniformMaxFraction)")
        }
    }

    @Test
    fun trivialConcentratesOnShard0() {
        val store = trivialStore()
        uniformCorpus(uniformN).forEach { store.add(it) }
        // Counts only the single endpoint trivial sets up; trivial has one
        // shard by construction, so placing all rows there characterizes
        // concentration. Querying the other (unsetup) endpoints would error.
        assertEquals(uniformN.toLong(), countAt(splitShardHosts!![0]), "trivial must place all rows on its single shard")
    }

    @Test
    fun splitSpreadsUniformly() {
        val store = multiShardStore { s -> SplitShardPolicy(s) }
        uniformCorpus(uniformN).forEach { store.add(it) }
        assertUniformSpread(rowCounts())
    }

    @Test
    fun roundRobinSpreadsUniformly() {
        val store = multiShardStore { s -> RoundRobinShardPolicy(s) }
        uniformCorpus(uniformN).forEach { store.add(it) }
        assertUniformSpread(rowCounts())
    }

    @Test
    fun quadHashSpreadsUniformly() {
        val store = multiShardStore { s -> QuadHashShardPolicy(s) }
        uniformCorpus(uniformN).forEach { store.add(it) }
        assertUniformSpread(rowCounts())
    }

    @Test
    fun prefixClustersPerPrefixNotUniform() {
        val store = multiShardStore { s -> PrefixShardPolicy(s) }
        prefixCorpus(prefixM).forEach { store.add(it) }
        val counts = rowCounts()
        val total = counts.sum()
        assertEquals((prefixCount * prefixM).toLong(), total, "row count mismatch: $counts")
        // Per-prefix concentration: each shard total is a multiple of prefixM
        // (each prefix contributes 0 or prefixM to a shard). Continuous
        // uniform spreads violate this -- the positive prefix signature.
        for (c in counts) {
            assertTrue(c % prefixM == 0L, "prefix concentration violation: $c not a multiple of $prefixM; counts=$counts")
        }
        val populated = counts.count { it > 0 }
        assertTrue(populated >= 2, "prefix expected to populate >= 2 shards; counts=$counts")
    }
}
