package org.megras.id

import org.megras.data.graph.DoubleVectorValue
import org.megras.data.graph.FloatVectorValue
import org.megras.data.graph.LocalQuadValue
import org.megras.data.graph.LongVectorValue
import org.megras.data.graph.QuadValue
import org.megras.data.graph.StringValue
import org.megras.data.graph.LongValue
import org.megras.data.graph.DoubleValue
import org.megras.data.graph.URIValue
import org.megras.data.graph.TemporalValue
import org.megras.util.HashUtil
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Canonical content encoding of [QuadValue] for semantic-id hashing.
 *
 * ENCOUNTERED PROBLEM, RESOLVED BY CONSTRAINT: a content hash must agree with
 * [QuadValue] equality (equal values -> equal hash; unequal -> distinct, w.h.p.).
 * Floating-point equality in the hierarchy was internally inconsistent -- scalar
 * [DoubleValue] used `Double.equals` (doubleToLongBits: NaN lumped, +/-0 distinct)
 * while vectors used `contentEquals` (==: NaN lumped, +/-0 equal). No single
 * bit-encoding reproduces both. Vector equality was redefined bit-uniform (see
 * the equals/hashCode rework in QuadValue.kt) so a uniform encoding exists.
 *
 * ENCODING (PERMANENT -- changing it re-hashes every quad):
 *   frame := tag (1 byte) || length (4 bytes, big-endian) || body
 * The length prefix makes the concatenation of operand frames injective across
 * boundaries: no two distinct (s,p,o) triples share a byte string, so there are
 * no encoding-induced collisions (collisions then rest only on the digest).
 * Bodies: string/URI/temporal -> UTF-8 of the value; numeric -> big-endian;
 * doubles (scalar and vector elements) -> big-endian of doubleToLongBits; floats
 * -> big-endian of floatToIntBits; longs -> big-endian. NaN canonicalises via
 * the bit conversion; +/-0 are distinct. Tags are distinct per CONCRETE subtype:
 * LocalQuadValue and URIValue carry different tags (they are unequal by equality
 * even for a coincident IRI). Unknown QuadValue subclasses throw.
 *
 * The hash is SHA3-256 via [HashUtil] (multihash-wrapped), reusing the
 * object-store content-addressing infra. See the quad-semantic-id-redesign plan.
 */
internal object QuadValueCanonical {

    private const val TAG_STRING: Int = 1
    private const val TAG_LONG: Int = 2
    private const val TAG_DOUBLE: Int = 3
    private const val TAG_URI: Int = 4
    private const val TAG_LOCAL: Int = 5
    private const val TAG_TEMPORAL: Int = 6
    private const val TAG_FLOAT_VECTOR: Int = 7
    private const val TAG_DOUBLE_VECTOR: Int = 8
    private const val TAG_LONG_VECTOR: Int = 9

    /** Multihash id for the quad (s,p,o). Deterministic; agrees with QuadValue equality. */
    fun semanticId(s: QuadValue, p: QuadValue, o: QuadValue): SemanticId {
        val bytes = concatFrames(s, p, o)
        // HashUtil.hash returns a multihash (code||len||digest); that IS the id.
        val multihash = HashUtil.hash(ByteArrayInputStream(bytes))
        return SemanticId(multihash)
    }

    private fun concatFrames(a: QuadValue, b: QuadValue, c: QuadValue): ByteArray {
        val fa = frame(a)
        val fb = frame(b)
        val fc = frame(c)
        val out = ByteArrayOutputStream(fa.size + fb.size + fc.size)
        out.write(fa)
        out.write(fb)
        out.write(fc)
        return out.toByteArray()
    }

    private fun frame(v: QuadValue): ByteArray {
        val (tag, body) = bodyFor(v)
        val out = ByteArrayOutputStream(1 + 4 + body.size)
        DataOutputStream(out).use { d ->
            d.writeByte(tag)
            d.writeInt(body.size)
            d.write(body)
        }
        return out.toByteArray()
    }

    private fun bodyFor(v: QuadValue): Pair<Int, ByteArray> = when (v) {
        is StringValue -> TAG_STRING to utf8(v.value)
        is LongValue -> TAG_LONG to longBe(v.value)
        is DoubleValue -> TAG_DOUBLE to longBe(java.lang.Double.doubleToLongBits(v.value))
        is LocalQuadValue -> TAG_LOCAL to utf8(v.value)
        is URIValue -> TAG_URI to utf8(v.value)
        is TemporalValue -> TAG_TEMPORAL to utf8(v.value)
        is FloatVectorValue -> TAG_FLOAT_VECTOR to floatVectorBe(v.vector)
        is DoubleVectorValue -> TAG_DOUBLE_VECTOR to doubleVectorBe(v.vector)
        is LongVectorValue -> TAG_LONG_VECTOR to longVectorBe(v.vector)
        else -> throw IllegalStateException("no canonical encoding for QuadValue subclass: ${v::class}")
    }

    private fun utf8(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)
    private fun longBe(x: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(x).array()
    private fun floatVectorBe(a: FloatArray): ByteArray {
        val b = ByteBuffer.allocate(a.size * 4).order(ByteOrder.BIG_ENDIAN)
        for (x in a) b.putInt(java.lang.Float.floatToIntBits(x))
        return b.array()
    }
    private fun doubleVectorBe(a: DoubleArray): ByteArray {
        val b = ByteBuffer.allocate(a.size * 8).order(ByteOrder.BIG_ENDIAN)
        for (x in a) b.putLong(java.lang.Double.doubleToLongBits(x))
        return b.array()
    }
    private fun longVectorBe(a: LongArray): ByteArray {
        val b = ByteBuffer.allocate(a.size * 8).order(ByteOrder.BIG_ENDIAN)
        for (x in a) b.putLong(x)
        return b.array()
    }
}
