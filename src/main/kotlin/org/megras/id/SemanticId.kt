package org.megras.id

import org.megras.util.extensions.toBase64
import org.apache.commons.codec.binary.Base64
import java.io.Serializable

/**
 * A stable, storage-independent identifier for a quad, computed as a content
 * hash of (subject, predicate, object) -- see [semanticIdOf]. Held as the
 * multihash byte string produced by `org.megras.util.HashUtil` (SHA3-256);
 * this is the same content-addressing form used for object-store ids.
 *
 * NOT a storage pointer: it carries no information about where the quad is
 * stored and is reproducible by anyone from the quad's operands. Equal quads
 * (by [org.megras.data.graph.QuadValue] equality) produce equal ids; unequal
 * quads produce (probabilistically) unequal ids.
 *
 * Identity is content-based over the wrapped bytes. [toString] is the base64
 * of the multihash bytes for diagnostic/API use; it is NOT the identity (two
 * equal ids share bytes, hence share toString).
 *
 * SECURITY-SENSITIVE: the canonical encoding that feeds the hash is defined in
 * [QuadValueCanonical]. Changing the encoding, the digest, or the tag
 * assignments changes every existing id -- they are permanent. See the
 * quad-semantic-id-redesign plan.
 */
class SemanticId(private val multihash: ByteArray) : Serializable {

    init {
        require(multihash.isNotEmpty()) { "SemanticId requires non-empty multihash bytes" }
    }

    companion object {
        /**
         * Parses the [toString] form (base64 of the multihash bytes, url-safe)
         * back into a [SemanticId]. Returns null if [s] is not a valid multihash
         * encoding (wrong base64, unknown/varint code, wrong digest length).
         * Strictly validates structure via [org.megras.util.HashUtil.decodeMultihash];
         * the plain constructor only checks non-emptiness, so this is the path
         * for untrusted input (e.g. REST path params).
         */
        fun fromString(s: String): SemanticId? {
            val bytes = try {
                Base64(true).decode(s)
            } catch (e: Throwable) {
                return null
            }
            return fromBytes(bytes)
        }

        /**
         * Validates [bytes] as a multihash and wraps it, or returns null if
         * malformed. Use over the plain constructor for untrusted input.
         */
        fun fromBytes(bytes: ByteArray): SemanticId? = try {
            org.megras.util.HashUtil.decodeMultihash(bytes)
            SemanticId(bytes)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Raw multihash bytes (defensive copy). */
    fun bytes(): ByteArray = multihash.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SemanticId) return false
        return multihash.contentEquals(other.multihash)
    }

    override fun hashCode(): Int = multihash.contentHashCode()

    override fun toString(): String = multihash.toBase64()
}
