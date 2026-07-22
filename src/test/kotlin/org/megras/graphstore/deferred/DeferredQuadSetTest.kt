package org.megras.graphstore.deferred

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.megras.data.graph.*
import org.megras.graphstore.*
import org.megras.graphstore.db.PostgresStore
import org.megras.graphstore.db.dict.PostgresDictionary
import java.net.InetSocketAddress
import java.net.Socket
import java.sql.DriverManager

/**
 * Comprehensive test suite for [DeferredQuadSet] and [FilterDescriptor].
 *
 * Verifies:
 *  A. FilterDescriptor composition logic (pure unit tests)
 *  B. DeferredQuadSet with BasicQuadSet (in-memory)
 *  C. DeferredQuadSet with PostgresStore (requires Podman container on port 5433)
 *  D. Cross-backend parity (same queries produce same results on both stores)
 *
 * Self-skips C/D unless localhost:5433 is listening.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeferredQuadSetTest {

    // ─── Shared corpus ───
    private val s1 = QuadValue.of("http://ex/s1")
    private val s2 = QuadValue.of("http://ex/s2")
    private val sl = QuadValue.of("http://localhost/l1")
    private val p1 = QuadValue.of("http://ex/p1")
    private val p2 = QuadValue.of("http://ex/p2")
    private val ps = QuadValue.of("http://ex/ps")
    private val ou1 = QuadValue.of("http://ex/o1")
    private val ou2 = QuadValue.of("http://ex/o2")
    private val ou3 = QuadValue.of("http://ex/o3")

    private val corpus = listOf(
        Quad(s1, p1, ou1),
        Quad(s1, p2, ou2),
        Quad(s2, p1, ou3),
        Quad(s2, p2, DoubleValue(1.5)),
        Quad(sl, p1, StringValue("str-x")),
        Quad(s1, ps, StringValue("alpha bravo")),
        Quad(s2, ps, StringValue("bravo charlie")),
    )

    private fun norm(qs: QuadSet): Set<Triple<QuadValue, QuadValue, QuadValue>> =
        qs.map { Triple(it.subject, it.predicate, it.`object`) }.toSet()

    private fun buildBasicQuadSet(): BasicQuadSet = BasicQuadSet(corpus.toSet())

    // ─── Postgres setup ───
    private var pgStore: PostgresStore? = null

    private fun pgAvailable(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("localhost", 5433), 200); true }
    } catch (_: Exception) {
        false
    }

    private fun resetSchema() {
        DriverManager.getConnection(
            "jdbc:postgresql://localhost:5433/megras_test", "megras", "megras"
        ).use { c ->
            c.createStatement().use { it.execute("DROP SCHEMA IF EXISTS megras CASCADE;") }
        }
    }

    @BeforeAll
    fun setupPg() {
        if (!pgAvailable()) return
        resetSchema()
        val dict = PostgresDictionary("localhost:5433/megras_test", "megras", "megras")
        val store = PostgresStore(dict, "localhost:5433/megras_test", "megras", "megras")
        dict.setup()
        store.setup()
        for (q in corpus) store.add(q)
        pgStore = store
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  A. FilterDescriptor unit tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `empty descriptor is trivial`() {
        assertTrue(FilterDescriptor.EMPTY.isTrivial)
        assertFalse(FilterDescriptor.EMPTY.isImpossible)
    }

    @Test
    fun `mergeFilter intersects subjects`() {
        val d1 = FilterDescriptor.EMPTY.mergeFilter(setOf(s1), null, null)
        val d2 = d1.mergeFilter(setOf(s1, s2), null, null)
        assertEquals(setOf(s1), d2.subjects)
    }

    @Test
    fun `mergeFilter with null wildcard`() {
        val d = FilterDescriptor.EMPTY
            .mergeFilter(null, setOf(p1), null)
            .mergeFilter(setOf(s1), null, null)
        assertEquals(setOf(s1), d.subjects)
        assertEquals(setOf(p1), d.predicates)
    }

    @Test
    fun `mergeFilter empty set makes impossible`() {
        val d = FilterDescriptor.EMPTY.mergeFilter(emptySet(), null, null)
        assertTrue(d.isImpossible)
    }

    @Test
    fun `withRangeFilter accumulates`() {
        val d = FilterDescriptor.EMPTY
            .withRangeFilter(p1, 0.0, 10.0)
            .withRangeFilter(p2, 5.0, null)
        assertEquals(2, d.rangeFilters.size)
    }

    @Test
    fun `depth increments on each merge`() {
        val d = FilterDescriptor.EMPTY
            .mergeFilter(setOf(s1), null, null)
            .mergeFilter(null, setOf(p1), null)
            .mergeFilter(null, null, setOf(ou1))
        assertEquals(3, d.depth)
    }

    @Test
    fun `isComposable false at circuit breaker depth`() {
        var d = FilterDescriptor.EMPTY
        for (i in 1..FilterDescriptor.CIRCUIT_BREAKER_DEPTH) {
            d = d.mergeFilter(setOf(s1), null, null)
        }
        assertFalse(d.isComposable)
    }

    @Test
    fun `withOrdering takes minimum limit`() {
        val d = FilterDescriptor.EMPTY
            .withOrdering(listOf(OrderSpec(QuadComponent.SUBJECT, true)), limit = 100)
            .withOrdering(listOf(OrderSpec(QuadComponent.OBJECT, false)), limit = 10)
        assertEquals(10, d.limit)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  B. DeferredQuadSet with BasicQuadSet (in-memory)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `bqs filterSubject returns matching quads`() {
        val qs = DeferredQuadSet.from(buildBasicQuadSet())
        val result = norm(qs.filterSubject(s1))
        assertEquals(
            setOf(
                Triple(s1, p1, ou1),
                Triple(s1, p2, ou2),
                Triple(s1, ps, StringValue("alpha bravo"))
            ),
            result
        )
    }

    @Test
    fun `bqs filterPredicate returns matching quads`() {
        val qs = DeferredQuadSet.from(buildBasicQuadSet())
        val result = norm(qs.filterPredicate(p1))
        assertEquals(
            setOf(
                Triple(s1, p1, ou1),
                Triple(s2, p1, ou3),
                Triple(sl, p1, StringValue("str-x"))
            ),
            result
        )
    }

    @Test
    fun `bqs filterObject returns matching quads`() {
        val qs = DeferredQuadSet.from(buildBasicQuadSet())
        val result = norm(qs.filterObject(ou1))
        assertEquals(setOf(Triple(s1, p1, ou1)), result)
    }

    @Test
    fun `bqs chained filter composes correctly`() {
        val qs = DeferredQuadSet.from(buildBasicQuadSet())
        val result = norm(qs.filter(setOf(s1), setOf(p1), null))
        assertEquals(setOf(Triple(s1, p1, ou1)), result)
    }

    @Test
    fun `bqs filterRange on DoubleValue`() {
        val qs = DeferredQuadSet.from(buildBasicQuadSet())
        val result = norm(qs.filterRange(p2, 0.0, 2.0))
        assertEquals(setOf(Triple(s2, p2, DoubleValue(1.5))), result)
    }

    @Test
    fun `bqs filterRange excludes out of range`() {
        val qs = DeferredQuadSet.from(buildBasicQuadSet())
        val result = norm(qs.filterRange(p2, 5.0, 10.0))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `bqs filterNotIn excludes values`() {
        val qs = DeferredQuadSet.from(buildBasicQuadSet())
        val result = norm(qs.filterNotIn(p1, setOf(ou1)))
        assertEquals(
            setOf(
                Triple(s2, p1, ou3),
                Triple(sl, p1, StringValue("str-x"))
            ),
            result
        )
    }

    @Test
    fun `bqs orderedFilter sorts by object ascending`() {
        val qs = DeferredQuadSet.from(buildBasicQuadSet())
        val result = qs.orderedFilter(
            null, setOf(p1), null,
            listOf(OrderSpec(QuadComponent.OBJECT, true)),
            limit = Int.MAX_VALUE
        ).toList()
        val objects = result.map { it.`object`.toString() }
        assertEquals(objects.sorted(), objects)
    }

    @Test
    fun `bqs orderedFilter with limit`() {
        val qs = DeferredQuadSet.from(buildBasicQuadSet())
        val result = qs.orderedFilter(
            null, setOf(p1), null,
            listOf(OrderSpec(QuadComponent.OBJECT, true)),
            limit = 2
        )
        assertEquals(2, result.size)
    }

    @Test
    fun `bqs orderedFilter with offset`() {
        val qs = DeferredQuadSet.from(buildBasicQuadSet())
        val all = qs.orderedFilter(
            null, setOf(p1), null,
            listOf(OrderSpec(QuadComponent.OBJECT, true)),
            limit = Int.MAX_VALUE
        ).toList()
        val offset = qs.orderedFilter(
            null, setOf(p1), null,
            listOf(OrderSpec(QuadComponent.OBJECT, true)),
            limit = 1,
            offset = 1
        ).toList()
        assertEquals(1, offset.size)
        assertEquals(all[1], offset[0])
    }

    @Test
    fun `bqs impossible filter returns empty`() {
        val qs = DeferredQuadSet.from(buildBasicQuadSet())
        val result = qs.filter(emptySet(), null, null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `bqs trivial deferred materializes to source`() {
        val bqs = buildBasicQuadSet()
        val deferred = DeferredQuadSet.from(bqs)
        assertSame(bqs, deferred.materialize())
    }

    @Test
    fun `bqs exists delegates to source`() {
        val qs = DeferredQuadSet.from(buildBasicQuadSet())
        // s1+p1 exists (Quad(s1, p1, ou1))
        assertTrue(qs.exists(s1, p1))
        // s1+p2 exists (Quad(s1, p2, ou2))
        assertTrue(qs.exists(s1, p2))
        // s1+ps exists (Quad(s1, ps, StringValue("alpha bravo")))
        assertTrue(qs.exists(s1, ps))
        // s3 doesn't exist at all
        val s3 = QuadValue.of("http://ex/s3")
        assertFalse(qs.exists(s3, p1))
        assertFalse(qs.exists(s3, p2))
        // p2 has no entry for sl
        assertFalse(qs.exists(sl, p2))
    }

    @Test
    fun `bqs size reflects materialized result`() {
        val qs = DeferredQuadSet.from(buildBasicQuadSet())
        // All 7 quads
        assertEquals(7, qs.size)
        // After filtering by s1 → 3 quads
        assertEquals(3, qs.filterSubject(s1).size)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  C. DeferredQuadSet with PostgresStore (requires container)
    // ═══════════════════════════════════════════════════════════════════════

    private fun pg(): PostgresStore {
        assumeTrue(pgStore != null, "Postgres not available on localhost:5433")
        return pgStore!!
    }

    @Test
    fun `pg filterSubject returns matching quads`() {
        val qs = DeferredQuadSet.from(pg())
        val result = norm(qs.filterSubject(s1))
        assertEquals(
            setOf(
                Triple(s1, p1, ou1),
                Triple(s1, p2, ou2),
                Triple(s1, ps, StringValue("alpha bravo"))
            ),
            result
        )
    }

    @Test
    fun `pg filterPredicate returns matching quads`() {
        val qs = DeferredQuadSet.from(pg())
        val result = norm(qs.filterPredicate(p1))
        assertEquals(
            setOf(
                Triple(s1, p1, ou1),
                Triple(s2, p1, ou3),
                Triple(sl, p1, StringValue("str-x"))
            ),
            result
        )
    }

    @Test
    fun `pg chained filter composes correctly`() {
        val qs = DeferredQuadSet.from(pg())
        val result = norm(qs.filter(setOf(s1), setOf(p1), null))
        assertEquals(setOf(Triple(s1, p1, ou1)), result)
    }

    @Test
    fun `pg filterRange on DoubleValue`() {
        val qs = DeferredQuadSet.from(pg())
        val result = norm(qs.filterRange(p2, 0.0, 2.0))
        assertEquals(setOf(Triple(s2, p2, DoubleValue(1.5))), result)
    }

    @Test
    fun `pg filterNotIn excludes values`() {
        val qs = DeferredQuadSet.from(pg())
        val result = norm(qs.filterNotIn(p1, setOf(ou1)))
        assertEquals(
            setOf(
                Triple(s2, p1, ou3),
                Triple(sl, p1, StringValue("str-x"))
            ),
            result
        )
    }

    @Test
    fun `pg orderedFilter sorts and limits`() {
        val qs = DeferredQuadSet.from(pg())
        val result = qs.orderedFilter(
            null, setOf(p1), null,
            listOf(OrderSpec(QuadComponent.OBJECT, true)),
            limit = 2
        )
        assertEquals(2, result.size)
        val objects = result.toList().map { it.`object`.toString() }
        assertEquals(objects.sorted(), objects)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  D. Cross-backend parity
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `parity same results on BasicQuadSet and PostgresStore`() {
        val pg = pg()
        val bqs = buildBasicQuadSet()

        val queries: List<Pair<String, (QuadSet) -> QuadSet>> = listOf(
            "filterSubject s1" to { it.filterSubject(s1) },
            "filterPredicate p1" to { it.filterPredicate(p1) },
            "filterObject ou1" to { it.filterObject(ou1) },
            "filter s1+p1" to { it.filter(setOf(s1), setOf(p1), null) },
            "filter s1+s2 + p1" to { it.filter(setOf(s1, s2), setOf(p1), null) },
            "filterRange p2 [0,2]" to { it.filterRange(p2, 0.0, 2.0) },
            "filterNotIn p1 - ou1" to { it.filterNotIn(p1, setOf(ou1)) },
        )

        for ((name, query) in queries) {
            val bqsResult = norm(DeferredQuadSet.from(bqs).let(query))
            val pgResult = norm(DeferredQuadSet.from(pg).let(query))
            assertEquals(bqsResult, pgResult, "Parity failed for: $name")
        }
    }

    @Test
    fun `parity composed deferred filter matches sequential on PostgresStore`() {
        val pg = pg()

        // Deferred: compose then materialize
        val deferred = DeferredQuadSet.from(pg)
            .filter(setOf(s1, s2), null, null)
            .filterPredicate(p1)
        val deferredResult = norm(deferred)

        // Sequential: apply filters one at a time on the raw store
        val sequentialResult = norm(pg.filter(setOf(s1, s2), null, null).filterPredicate(p1))

        assertEquals(sequentialResult, deferredResult)
    }

    @Test
    fun `parity filterRange composition matches sequential on PostgresStore`() {
        val pg = pg()

        val deferredResult = norm(
            DeferredQuadSet.from(pg)
                .filterPredicate(p2)
                .filterRange(p2, 0.0, 2.0)
        )

        val sequentialResult = norm(
            pg.filterPredicate(p2).filterRange(p2, 0.0, 2.0)
        )

        assertEquals(sequentialResult, deferredResult)
    }

    @Test
    fun `parity orderedFilter composition matches sequential on PostgresStore`() {
        val pg = pg()

        val orderSpec = listOf(OrderSpec(QuadComponent.OBJECT, true))
        val deferredResult = DeferredQuadSet.from(pg)
            .filterPredicate(p1)
            .orderedFilter(null, null, null, orderSpec, limit = 2)
            .toList()

        val sequentialResult = pg
            .filterPredicate(p1)
            .orderedFilter(null, null, null, orderSpec, limit = 2)
            .toList()

        assertEquals(
            sequentialResult.map { Triple(it.subject, it.predicate, it.`object`) },
            deferredResult.map { Triple(it.subject, it.predicate, it.`object`) }
        )
    }

    @Test
    fun `parity multi-step chain on PostgresStore`() {
        val pg = pg()

        // Complex chain: filter by subjects, then by predicate, then range, then order+limit
        val orderSpec = listOf(OrderSpec(QuadComponent.OBJECT, false))
        val deferredResult = DeferredQuadSet.from(pg)
            .filter(setOf(s1, s2), null, null)
            .filterRange(p2, 0.0, 10.0)
            .orderedFilter(null, null, null, orderSpec, limit = 5, offset = 0)
            .toList()

        // Sequential equivalent
        val sequentialResult = pg
            .filter(setOf(s1, s2), null, null)
            .filterRange(p2, 0.0, 10.0)
            .orderedFilter(null, null, null, orderSpec, limit = 5, offset = 0)
            .toList()

        assertEquals(
            sequentialResult.map { Triple(it.subject, it.predicate, it.`object`) },
            deferredResult.map { Triple(it.subject, it.predicate, it.`object`) }
        )
    }

    @Test
    fun `parity deferred and sequential produce same size on full scan`() {
        val pg = pg()
        val deferred = DeferredQuadSet.from(pg)
        val sequential = pg

        assertEquals(sequential.size, deferred.size)
        assertEquals(norm(sequential), norm(deferred))
    }
}
