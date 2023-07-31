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

    private fun digest(hashType: HashType) = MessageDigest.getInstance(hashType.algorithm)

    private fun updateDigest(digest: MessageDigest, data: InputStream): MessageDigest {
        val buffer = ByteArray(STREAM_BUFFER_LENGTH)
        var read = data.read(buffer, 0, STREAM_BUFFER_LENGTH)
        while (read > -1) {
            digest.update(buffer, 0, read)
            read = data.read(buffer, 0, STREAM_BUFFER_LENGTH)
        }
        return digest
    }

    fun hash(stream: InputStream, hashType: HashType = HashType.SHA3_256): ByteArray = updateDigest(digest(hashType), stream).digest()

    fun hash(string: String, hashType: HashType = HashType.SHA3_256): ByteArray = updateDigest(digest(hashType), ByteArrayInputStream(string.toByteArray(Charsets.UTF_8))).digest()

    fun hash(mask: BitSet, hashType: HashType = HashType.SHA3_256): ByteArray = updateDigest(digest(hashType), ByteArrayInputStream(mask.toByteArray())).digest()

    fun hashToHex(stream: InputStream, hashType: HashType = HashType.SHA3_256): String = hash(stream, hashType).toHex()

    fun hashToBase32(stream: InputStream, hashType: HashType = HashType.SHA3_256): String = hash(stream, hashType).toBase32()

    fun hashToBase64(stream: InputStream, hashType: HashType = HashType.SHA3_256): String = hash(stream, hashType).toBase64()

    fun hashToBase64(string: String, hashType: HashType = HashType.SHA3_256): String = hash(string, hashType).toBase64()

    fun hashToBase64(mask: BitSet, hashType: HashType = HashType.SHA3_256): String = hash(mask, hashType).toBase64()

    enum class HashType(val algorithm: String) {
        SHA3_256("SHA3-256"),
        MD5("MD5")
    }

}