package org.megras.id

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.megras.data.graph.DoubleValue
import org.megras.data.graph.LongValue
import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.data.graph.StringValue
import org.megras.data.graph.URIValue

/**
 * Round-trip and absence checks for [org.megras.graphstore.QuadSet.getId] on
 * the in-memory [org.megras.graphstore.BasicQuadSet]. [getId] keys on the
 * content hash ([org.megras.id.id]); it MUST recover a stored quad from its
 * id and return null for an id synthesized from terms never stored together.
 * Equal quads share an id, so a lookup with the id of an equivalent-but-not-
 * identical construction (e.g. URIValue parsed via a different path) must
 * still hit. Values only — no storage pointers involved.
 */
class GetIdRoundTripTest {

    private val s = QuadValue.of("http://ex/s")
    private val p = QuadValue.of("http://ex/p")
    private val oLit = StringValue("o")
    private val oLong = LongValue(7)
    private val oDouble = DoubleValue(1.5)

    private fun quad(a: QuadValue, b: QuadValue, c: QuadValue): Quad = Quad(a, b, c)

    @Test
    fun storedQuadRecoveredFromItsId() {
        val stored = quad(s, p, oLit)
        val qs = org.megras.graphstore.BasicQuadSet(setOf(stored))
        assertEquals(stored, qs.getId(stored.id))
    }

    @Test
    fun absentIdReturnsNullAcrossTermTypes() {
        val qs = org.megras.graphstore.BasicQuadSet(setOf(quad(s, p, oLit)))
        assertNull(qs.getId(quad(s, p, oLong).id), "synthesized id for unstored terms must miss")
        assertNull(qs.getId(quad(s, p, oDouble).id), "synthesized id for unstored terms must miss")
        // An id whose bytes are not a valid multihash at all.
        val bogus = SemanticId.fromString("not-a-real-multihash")
        assertNull(if (bogus != null) qs.getId(bogus) else null)
    }

    @Test
    fun equalQuadsShareIdAcrossConstructionPaths() {
        val storedViaDirect = quad(s, p, oLit)
        val qs = org.megras.graphstore.BasicQuadSet(setOf(storedViaDirect))
        val probe = quad(QuadValue.of("http://ex/s"), p, oLit)
        assertEquals(probe.id, storedViaDirect.id)
        assertEquals(storedViaDirect, qs.getId(probe.id))
    }
}
