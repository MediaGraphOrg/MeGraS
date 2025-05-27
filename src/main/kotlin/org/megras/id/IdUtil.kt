package org.megras.id

import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.pixels.Pixel
import org.megras.data.fs.file.PseudoFile
import org.megras.data.mime.MimeType
import org.megras.data.model.MediaType
import org.megras.util.HashUtil
import org.megras.util.extensions.toBase64
import java.io.File
import java.io.FileInputStream
import kotlin.experimental.or


object IdUtil {

    fun getMediaType(id: String): MediaType = if (id.isBlank()) {
        MediaType.UNKNOWN
    } else {
        MediaType.prefixMap[id[0]] ?: MediaType.UNKNOWN
    }

    fun generateId(file: PseudoFile): ObjectId {

        val mimeType = MimeType.mimeType(file.extension)
        val mediaType = MediaType.mimeTypeMap[mimeType] ?: MediaType.UNKNOWN

        return ObjectId(
            when (mediaType) {
                MediaType.TEXT -> {"${MediaType.TEXT.prefix}${HashUtil.hash(file.inputStream()).toBase64()}"}
                MediaType.IMAGE -> {

                    //computing simple perceptual hash
                    fun luminance(pixel: Pixel): Float =
                        0.2126f * pixel.red() + 0.7152f * pixel.green() + 0.0722f * pixel.blue()

                    val luminances = mutableListOf<Float>()

                    val imageBytes = file.bytes() // Read bytes once
                    ImmutableImage.loader().fromBytes(imageBytes).resizeTo(8, 8).forEach { luminances.add(luminance(it)) }

                    val meanLuminance = luminances.sum() / 64f

                    val bits = luminances.map { it > meanLuminance }

                    val pHashBytes = ByteArray(8)

                    for (i in 0..7) {
                        for (j in 0..7) {
                            if (bits[8 * i + j]) {
                                pHashBytes[i] = pHashBytes[i] or ((1 shl j).toByte())
                            }
                        }
                    }

                    // Combine perceptual hash bytes and the full image bytes before hashing
                    // This creates a single multihash that encompasses both
                    val combinedBytes = pHashBytes + imageBytes
                    MediaType.IMAGE.prefix + HashUtil.hash(combinedBytes.inputStream()).toBase64()

                }
                MediaType.AUDIO -> {"${MediaType.AUDIO.prefix}${HashUtil.hash(file.inputStream()).toBase64()}"}
                MediaType.VIDEO -> {"${MediaType.VIDEO.prefix}${HashUtil.hash(file.inputStream()).toBase64()}"}
                MediaType.DOCUMENT -> {"${MediaType.DOCUMENT.prefix}${HashUtil.hash(file.inputStream()).toBase64()}"}
                MediaType.MESH -> {"${MediaType.MESH.prefix}${HashUtil.hash(file.inputStream()).toBase64()}"}
                MediaType.UNKNOWN -> "${MediaType.UNKNOWN.prefix}${HashUtil.hash(file.inputStream()).toBase64()}"
            }.trim())
    }

}