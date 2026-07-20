package org.megras.id

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.megras.data.graph.DoubleValue
import org.megras.data.graph.DoubleVectorValue
import org.megras.data.graph.FloatVectorValue
import org.megras.data.graph.LocalQuadValue
import org.megras.data.graph.LongValue
import org.megras.data.graph.LongVectorValue
import org.megras.data.graph.QuadValue
import org.megras.data.graph.StringValue
import org.megras.data.graph.URIValue
import org.megras.data.graph.TemporalValue

/**
 * Correctness of the canonical content encoding that backs [SemanticId].
 * The encoding must agree with QuadValue equality (the reference for "same
 * quad") and resist cross-type/frame-boundary collisions; the hash itself is
 * only trustworthy if its input encoding is injective across distinct triples.
 *
 * NOT pinned to recorded digest values here -- pinning (a constant expected
 * digest per fixture) would guard against unintended encoding changes, which
 * silently invalidate every existing id. TODO: add pinned-digest fixtures once
 * the encoding is review-approved; until then determinism + consistency hold.
 */
class QuadValueCanonicalTest {

    private fun id(s: QuadValue, p: QuadValue, o: QuadValue): SemanticId =
        QuadValueCanonical.semanticId(s, p, o)

    private val s = QuadValue.of("http://ex/s")!!
    private val p = QuadValue.of("http://ex/p")!!
    private val o = QuadValue.of("http://ex/o")!!

    @Test
    fun deterministic() {
        assertEquals(id(s, p, o), id(s, p, o))
        assertEquals(id(s, p, o).hashCode(), id(s, p, o).hashCode())
    }

    @Test
    fun operandOrderMatters() {
        assertNotEquals(id(s, p, o), id(p, s, o))
        assertNotEquals(id(s, p, o), id(s, o, p))
        assertNotEquals(id(s, p, o), id(o, p, s))
    }

    @Test
    fun equalValuesProduceEqualIds() {
        val s2 = QuadValue.of("http://ex/s")!!
        assertEquals(id(s, p, o), id(s2, p, o))
    }

    @Test
    fun unequalValuesProduceDistinctIds() {
        val sx = QuadValue.of("http://ex/x")!!
        assertNotEquals(id(s, p, o), id(sx, p, o))
        assertNotEquals(id(s, p, o), id(s, p, sx))
    }

    @Test
    fun crossTypeCollisionsAvoided() {
        // Disjoint QuadValue subtypes with otherwise-similar textual content
        // must yield distinct ids (distinct type tags).
        val lng = LongValue(0L)
        val dbl = DoubleValue(0.0)
        val str = StringValue("0")
        val urv = URIValue("0")
        val vals = listOf(lng, dbl, str, urv)
        val ids = vals.map { id(s, p, it) }
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            assertNotEquals(ids[i], ids[j], "cross-type collision between ${vals[i]} and ${vals[j]}")
        }
    }

    @Test
    fun localQuadValueVersusURIValueCoincidentIri() {
        // LocalQuadValue and URIValue are unequal by equality even when their
        // full IRIs coincide; their ids must differ (distinct tags). Construct
        // a URIValue with the local's full IRI text directly -- QuadValue.of
        // would reroute it to LocalQuadValue, defeating the test.
        val local = LocalQuadValue("x")
        val urv = URIValue(local.value)
        assertEquals(local.value, urv.value, "fixture: IRI texts must coincide for this test")
        assertNotEquals(local, urv, "fixture: local and URI must be unequal by equality")
        assertNotEquals(id(s, p, local), id(s, p, urv))
    }

    @Test
    fun nanCanonicalisedAcrossPayloads() {
        // All NaN bit patterns canonicalise via floatToIntBits -> equal vectors
        // -> equal ids.
        val nanA = FloatVectorValue(floatArrayOf(Float.NaN, 1f))
        val nanB = FloatVectorValue(floatArrayOf(java.lang.Float.intBitsToFloat(0x7fc00001), 1f))
        assertEquals(nanA, nanB, "fixture: NaN payloads must be equal under bit-uniform equality")
        assertEquals(id(s, p, nanA), id(s, p, nanB))
    }

    @Test
    fun signedZeroDistinct() {
        // +0/-0 are distinct under bit-uniform equality -> distinct ids, for
        // scalar doubles and vector elements alike.
        assertNotEquals(id(s, p, DoubleValue(+0.0)), id(s, p, DoubleValue(-0.0)))
        assertNotEquals(
            id(s, p, FloatVectorValue(floatArrayOf(+0f))),
            id(s, p, FloatVectorValue(floatArrayOf(-0f)))
        )
    }

    @Test
    fun vectorElementTypeTagsSeparate() {
        // Same numeric value carried by different vector element types must
        // produce distinct ids (distinct tags per vector type).
        val fv = FloatVectorValue(floatArrayOf(1f))
        val dv = DoubleVectorValue(doubleArrayOf(1.0))
        val lv = LongVectorValue(longArrayOf(1L))
        val ids = listOf(fv, dv, lv).map { id(s, p, it) }
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            assertNotEquals(ids[i], ids[j])
        }
    }

    @Test
    fun frameBoundaryInjectionResistant() {
        // Two triples whose operand bodies sum to the same bytes but split
        // across different frame boundaries must produce distinct ids -- the
        // length-prefixed framing makes the frame sequence injective.
        val a = id(StringValue("ab"), StringValue("c"), o)
        val b = id(StringValue("a"), StringValue("bc"), o)
        assertNotEquals(a, b, "frame-boundary collision: length prefix failed to separate splits")
    }

    @Test
    fun temporalDistinctFromStringsAndUris() {
        val t = TemporalValue("2020-01-01T00:00:00Z")
        val strT = StringValue(t.toString())
        assertNotEquals(id(s, p, t), id(s, p, strT))
    }
}
