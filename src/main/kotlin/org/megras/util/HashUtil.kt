package org.megras.util

import org.megras.util.extensions.toBase32
import org.megras.util.extensions.toBase64
import org.megras.util.extensions.toHex
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.*

object HashUtil {

    private const val STREAM_BUFFER_LENGTH = 512

    //region Multihash Codes (These are standard multicodec codes)
    // Comprehensive list at https://github.com/multiformats/multicodec/blob/master/table.csv
    enum class MultihashCode(val code: Int, val digestLength: Int? = null) {
        SHA3_256(0x12, 32), // 0x12 is the multicodec code for SHA3-256, 32 bytes is the digest length
        MD5(0xd4, 16);      // 0xd4 is the multicodec code for MD5, 16 bytes is the digest length
        // Add more as needed, e.g., SHA2-256, SHA2-512, Blake2b, etc.

        companion object {
            private val BY_CODE = enumValues<MultihashCode>().associateBy { it.code }
            fun fromCode(code: Int): MultihashCode? = BY_CODE[code]
        }
    }
    //endregion

    enum class HashType(val algorithm: String, val multihashCode: MultihashCode) {
        SHA3_256("SHA3-256", MultihashCode.SHA3_256),
        MD5("MD5", MultihashCode.MD5)
    }

    private fun getMessageDigest(hashType: HashType) = MessageDigest.getInstance(hashType.algorithm)

    private fun updateDigest(digest: MessageDigest, data: InputStream): MessageDigest {
        val buffer = ByteArray(STREAM_BUFFER_LENGTH)
        var read = data.read(buffer, 0, STREAM_BUFFER_LENGTH)
        while (read > -1) {
            digest.update(buffer, 0, read)
            read = data.read(buffer, 0, STREAM_BUFFER_LENGTH)
        }
        return digest
    }

    /**
     * Calculates the raw cryptographic digest of the input.
     * This is an internal helper; public `hash` functions will return multihashes.
     */
    private fun calculateRawDigest(stream: InputStream, hashType: HashType): ByteArray = updateDigest(getMessageDigest(hashType), stream).digest()

    private fun calculateRawDigest(string: String, hashType: HashType): ByteArray = updateDigest(getMessageDigest(hashType), ByteArrayInputStream(string.toByteArray(Charsets.UTF_8))).digest()

    private fun calculateRawDigest(mask: BitSet, hashType: HashType): ByteArray = updateDigest(getMessageDigest(hashType), ByteArrayInputStream(mask.toByteArray())).digest()


    //region Public Hash Functions (These now always return Multihashes)
    /**
     * Hashes the given input stream and returns the result as a multihash byte array.
     * This is the primary function for generating hashes in a multihash-enabled system.
     */
    fun hash(stream: InputStream, hashType: HashType = HashType.SHA3_256): ByteArray {
        val digestValue = calculateRawDigest(stream, hashType)
        val codeBytes = encodeVarint(hashType.multihashCode.code)
        val lengthBytes = encodeVarint(digestValue.size)
        return codeBytes + lengthBytes + digestValue
    }

    /**
     * Hashes the given string and returns the result as a multihash byte array.
     */
    fun hash(string: String, hashType: HashType = HashType.SHA3_256): ByteArray {
        val digestValue = calculateRawDigest(string, hashType)
        val codeBytes = encodeVarint(hashType.multihashCode.code)
        val lengthBytes = encodeVarint(digestValue.size)
        return codeBytes + lengthBytes + digestValue
    }

    /**
     * Hashes the given BitSet and returns the result as a multihash byte array.
     */
    fun hash(mask: BitSet, hashType: HashType = HashType.SHA3_256): ByteArray {
        val digestValue = calculateRawDigest(mask, hashType)
        val codeBytes = encodeVarint(hashType.multihashCode.code)
        val lengthBytes = encodeVarint(digestValue.size)
        return codeBytes + lengthBytes + digestValue
    }
    //endregion

    //region Multihash Decoding Functions
    /**
     * Represents a decoded multihash.
     */
    data class DecodedMultihash(
        val multihashCode: MultihashCode,
        val digestLength: Int,
        val digestValue: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as DecodedMultihash

            if (multihashCode != other.multihashCode) return false
            if (digestLength != other.digestLength) return false
            if (!digestValue.contentEquals(other.digestValue)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = multihashCode.hashCode()
            result = 31 * result + digestLength
            result = 31 * result + digestValue.contentHashCode()
            return result
        }
    }

    /**
     * Decodes a multihash byte array into its components.
     * Throws IllegalArgumentException if the multihash is malformed or an unknown hash code.
     */
    fun decodeMultihash(multihashBytes: ByteArray): DecodedMultihash {
        val inputStream = ByteArrayInputStream(multihashBytes)

        // Read multihash code
        val (code, codeBytesRead) = decodeVarint(inputStream)
        val multihashCode = MultihashCode.fromCode(code)
            ?: throw IllegalArgumentException("Unknown multihash code: 0x${code.toString(16)}")

        // Read digest length
        val (digestLength, lengthBytesRead) = decodeVarint(inputStream)

        // Verify expected digest length if available in enum
        multihashCode.digestLength?.let { expectedLength ->
            if (digestLength != expectedLength) {
                throw IllegalArgumentException("Mismatch in digest length. Expected: $expectedLength, Got: $digestLength for hash code 0x${multihashCode.code.toString(16)}")
            }
        }

        // Read digest value
        val digestValue = ByteArray(digestLength)
        val bytesRead = inputStream.read(digestValue)
        if (bytesRead != digestLength) {
            throw IllegalArgumentException("Truncated multihash. Expected $digestLength bytes for digest, got $bytesRead.")
        }

        return DecodedMultihash(multihashCode, digestLength, digestValue)
    }
    //endregion

    //region Varint Encoding/Decoding
    /**
     * Encodes an integer as a varint byte array.
     */
    private fun encodeVarint(value: Int): ByteArray {
        if (value < 0) throw IllegalArgumentException("Varint encoding only supports non-negative integers.")
        val bytes = mutableListOf<Byte>()
        var n = value
        while (true) {
            val byte = (n and 0x7F)
            n = n ushr 7
            if (n == 0) {
                bytes.add(byte.toByte())
                break
            } else {
                bytes.add((byte or 0x80).toByte())
            }
        }
        return bytes.toByteArray()
    }

    /**
     * Decodes a varint from an input stream.
     * Returns a Pair of (decoded value, number of bytes read).
     */
    private fun decodeVarint(inputStream: InputStream): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var bytesRead = 0
        while (true) {
            val byte = inputStream.read()
            if (byte == -1) {
                throw IllegalArgumentException("Unexpected end of stream while decoding varint.")
            }
            bytesRead++
            result = result or ((byte and 0x7F) shl shift)
            if ((byte and 0x80) == 0) {
                break
            }
            shift += 7
            if (shift >= 32) { // Prevent overflow for very large varints (though multihash codes are usually small)
                throw IllegalArgumentException("Varint too large or malformed.")
            }
        }
        return Pair(result, bytesRead)
    }
    //endregion

    //region Convenient Hash-to-String Functions (for multihashes)
    // These functions now always return the string representation of a multihash
    fun hashToHex(stream: InputStream, hashType: HashType = HashType.SHA3_256): String = hash(stream, hashType).toHex()

    fun hashToBase32(stream: InputStream, hashType: HashType = HashType.SHA3_256): String = hash(stream, hashType).toBase32()

    fun hashToBase64(stream: InputStream, hashType: HashType = HashType.SHA3_256): String = hash(stream, hashType).toBase64()

    fun hashToBase64(string: String, hashType: HashType = HashType.SHA3_256): String = hash(string, hashType).toBase64()

    fun hashToBase64(mask: BitSet, hashType: HashType = HashType.SHA3_256): String = hash(mask, hashType).toBase64()
    //endregion
}