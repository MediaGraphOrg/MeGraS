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
                MediaType.TEXT -> {"${MediaType.TEXT.prefix}${HashUtil.hashToBase64(file.inputStream())}"}
                MediaType.IMAGE -> {

                    //computing simple perceptual hash
                    fun luminance(pixel: Pixel): Float =
                        0.2126f * pixel.red() + 0.7152f * pixel.green() + 0.0722f * pixel.blue()

                    val luminances = mutableListOf<Float>()

                    ImmutableImage.loader().fromBytes(file.bytes()).resizeTo(8, 8).forEach { luminances.add(luminance(it)) }

                    val meanLuminance = luminances.sum() / 64f

                    val bits = luminances.map { it > meanLuminance }

                    val bytes = ByteArray(8)

                    for (i in 0..7) {
                        for (j in 0..7) {
                            if (bits[8 * i + j]) {
                                bytes[i] = bytes[i] or ((1 shl j).toByte())
                            }
                        }
                    }

                    //prefix + phash + file hash
                    MediaType.IMAGE.prefix + (bytes + HashUtil.hash(file.inputStream())).toBase64()

                }
                MediaType.AUDIO -> {"${MediaType.AUDIO.prefix}${HashUtil.hashToBase64(file.inputStream())}"}
                MediaType.VIDEO -> {"${MediaType.VIDEO.prefix}${HashUtil.hashToBase64(file.inputStream())}"}
                MediaType.DOCUMENT -> {"${MediaType.DOCUMENT.prefix}${HashUtil.hashToBase64(file.inputStream())}"}
                MediaType.MESH -> {"${MediaType.MESH.prefix}${HashUtil.hashToBase64(file.inputStream())}"}
                MediaType.UNKNOWN -> "${MediaType.UNKNOWN.prefix}${HashUtil.hashToBase64(file.inputStream())}"
            }.trim())
    }

}