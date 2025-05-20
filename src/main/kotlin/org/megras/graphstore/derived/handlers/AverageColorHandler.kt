package org.megras.graphstore.derived.handlers

import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.fs.FileUtil
import org.megras.data.fs.StoredObjectId
import org.megras.data.graph.FloatVectorValue
import org.megras.data.graph.LocalQuadValue
import org.megras.data.graph.StringValue
import org.megras.data.graph.URIValue
import org.megras.data.mime.MimeType
import org.megras.data.schema.MeGraS
import org.megras.graphstore.QuadSet
import org.megras.graphstore.derived.DerivedRelationHandler
import org.megras.util.Constants
import java.awt.image.BufferedImage
import java.io.IOException
import javax.imageio.ImageIO

class AverageColorHandler(private val quads: QuadSet, private val objectStore: FileSystemObjectStore) :
    DerivedRelationHandler<FloatVectorValue> {

    companion object {

        fun averageColor(bufferedImage: BufferedImage): FloatVectorValue {

            var r = 0f
            var g = 0f
            var b = 0f
            var a = 0f

            bufferedImage.getRGB(0, 0, bufferedImage.width, bufferedImage.height,
                null, 0, bufferedImage.width
            )
                .forEach { c ->
                    val alpha = (c shr 24 and 0xFF) / 255f
                    r += (c shr 16 and 0xFF) * alpha
                    g += (c shr 8 and 0xFF) * alpha
                    b += (c and 0xFF) * alpha
                    a += alpha
                }

            if (a <= 1f) {
                a = 1f
            }

            return FloatVectorValue(
                listOf(
                    r / a, g / a, b / a
                )
            )
        }
    }

    override val predicate: URIValue = URIValue("${Constants.DERIVED_PREFIX}/averageColor")

    override fun canDerive(subject: URIValue): Boolean {
        val osr = FileUtil.getOsr(subject, quads, objectStore) ?: return false

        return when (osr.descriptor.mimeType) { //TODO technically, video should also be supported
            MimeType.BMP,
            MimeType.GIF,
            MimeType.JPEG_I,
            MimeType.PNG,
            MimeType.TIFF -> true

            else -> false
        }

    }

    override fun derive(subject: URIValue): Collection<FloatVectorValue> {
        val osr = FileUtil.getOsr(subject, quads, objectStore) ?: return emptyList()

        val bufferedImage = try {
            ImageIO.read(osr.inputStream())
        } catch (_: IOException) {
            return emptyList()
        }
        return listOf(
            averageColor(bufferedImage)
        )
    }
}