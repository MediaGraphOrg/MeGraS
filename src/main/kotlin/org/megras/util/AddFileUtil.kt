package org.megras.util

import com.github.kokorin.jaffree.StreamType
import com.github.kokorin.jaffree.ffmpeg.*
import com.github.kokorin.jaffree.ffprobe.FFprobe
import de.javagl.obj.ObjReader
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.apache.pdfbox.pdmodel.PDDocument
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.fs.StoredObjectDescriptor
import org.megras.data.fs.file.PseudoFile
import org.megras.data.graph.Quad
import org.megras.data.graph.StringValue
import org.megras.data.mime.MimeType
import org.megras.data.model.MediaType
import org.megras.data.schema.MeGraS
import org.megras.graphstore.MutableQuadSet
import org.megras.id.IdUtil
import org.megras.id.ObjectId
import org.megras.segmentation.Bounds
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min


object AddFileUtil {

    fun addFile(objectStore: FileSystemObjectStore, quads: MutableQuadSet, file: PseudoFile): ObjectId {

        val existing = objectStore.exists(file)

        if (existing != null) {

            val id = quads.filter(null, listOf(MeGraS.RAW_ID.uri), listOf(StringValue(existing.id.id))).firstOrNull()?.subject as? ObjectId

            if (id != null) {
                return id
            }

        }

        //store raw
        val descriptor = objectStore.store(file)
        val oid = IdUtil.generateId(file)

        //generate and store canonical
        val canonical = generateCanonicalRepresentation(objectStore, descriptor)

        quads.addAll(
            listOf(
                Quad(oid, MeGraS.RAW_ID.uri, StringValue(descriptor.id.id)),
                Quad(oid, MeGraS.RAW_MIME_TYPE.uri, StringValue(descriptor.mimeType.mimeString)),
                Quad(oid, MeGraS.MEDIA_TYPE.uri, StringValue(MediaType.mimeTypeMap[descriptor.mimeType]!!.name)),
                Quad(oid, MeGraS.FILE_NAME.uri, StringValue(file.name)),
                Quad(oid, MeGraS.CANONICAL_ID.uri, StringValue(canonical.id.id)),
                Quad(oid, MeGraS.CANONICAL_MIME_TYPE.uri, StringValue(canonical.mimeType.mimeString))
            )
        )

        return oid
    }


    private fun generateCanonicalRepresentation(
        objectStore: FileSystemObjectStore,
        rawDescriptor: StoredObjectDescriptor
    ): StoredObjectDescriptor {

        return when (rawDescriptor.mimeType) {

            MimeType.PNG -> {
                val imageStream = objectStore.get(rawDescriptor.id)!!.inputStream()
                val image = ImageIO.read(imageStream)

                val descriptor = StoredObjectDescriptor(
                    rawDescriptor.id,
                    rawDescriptor.mimeType,
                    rawDescriptor.length,
                    Bounds(0, image.width, 0, image.height)
                )
                objectStore.store(imageStream, descriptor)

                //return
                descriptor
            }
            MimeType.JPEG_I,
            MimeType.BMP,
            MimeType.GIF,
            MimeType.SVG,
            MimeType.TIFF -> {

                try {
                    val buffered = ImageIO.read(objectStore.get(rawDescriptor.id)!!.inputStream())

                    val rgbaImage = BufferedImage(buffered.width, buffered.height, BufferedImage.TYPE_INT_ARGB)
                    val g = rgbaImage.createGraphics()
                    g.drawImage(buffered, 0, 0, null)
                    g.dispose()

                    val outStream = ByteArrayOutputStream()
                    ImageIO.write(rgbaImage, "PNG", outStream)

                    val buf = outStream.toByteArray()
                    val inStream = ByteArrayInputStream(buf)
                    val id = objectStore.idFromStream(inStream)

                    inStream.reset()

                    val descriptor = StoredObjectDescriptor(
                        id,
                        MimeType.PNG,
                        buf.size.toLong(),
                        Bounds(0, buffered.width, 0, buffered.height)
                    )
                    objectStore.store(inStream, descriptor)

                    //return
                    descriptor

                } catch (e: Exception) {
                    //TODO log
                    rawDescriptor
                }
            }

            MimeType.AAC,
            MimeType.OGG_A,
            MimeType.MPEG_A,
            MimeType.MP4_A,
            MimeType.ADP,
            MimeType.AIF,
            MimeType.AU,
            MimeType.MIDI,
            MimeType.WAV,
            MimeType.WAX,
            MimeType.WMA,
            MimeType.MKV,
            MimeType.WEBM,
            MimeType.MOV,
            MimeType.MP4,
            MimeType.AVI,
            MimeType.OGG -> {
                try {
                    val videoStream = objectStore.get(rawDescriptor.id)!!.byteChannel()
                    val outStream = SeekableInMemoryByteChannel()
                    val durationMillis = AtomicLong()

                    val probe = FFprobe.atPath().setShowStreams(true).setInput(videoStream).execute().streams
                    val videoProbe = probe.first { s -> s.codecType == StreamType.VIDEO }

                    FFmpeg.atPath()
                        .addInput(ChannelInput.fromChannel(videoStream))
                        .setProgressListener { progress -> durationMillis.set(progress.timeMillis) }
                        .addArguments("-c:v", "libvpx-vp9")
                        .addArguments("-c:a", "libvorbis")
                        .setOverwriteOutput(true)
                        .addOutput(ChannelOutput.toChannel("", outStream).setFormat("webm"))
                        .execute()

                    val buf = outStream.array()
                    val inStream = ByteArrayInputStream(buf)
                    val id = objectStore.idFromStream(inStream)

                    inStream.reset()

                    val descriptor = StoredObjectDescriptor(
                        id,
                        MimeType.WEBM,
                        buf.size.toLong(),
                        Bounds(0, videoProbe.width, 0, videoProbe.height, 0, durationMillis.get())
                    )
                    objectStore.store(inStream, descriptor)

                    //return
                    descriptor

                } catch (e: Exception) {
                    //TODO log
                    rawDescriptor
                }
            }

            MimeType.CSS,
            MimeType.CSV,
            MimeType.HTML,
            MimeType.JS,
            MimeType.JSON,
            MimeType.YAML,
            MimeType.TEXT -> {
                val textStream = objectStore.get(rawDescriptor.id)!!.inputStream()
                val buffer = textStream.readBytes()

                val descriptor = StoredObjectDescriptor(
                    rawDescriptor.id,
                    rawDescriptor.mimeType,
                    rawDescriptor.length,
                    Bounds(0, buffer.size)
                )
                objectStore.store(textStream, descriptor)

                //return
                descriptor
            }

            MimeType.PDF -> {
                val pdfStream = objectStore.get(rawDescriptor.id)!!.inputStream()
                val pdf = PDDocument.load(pdfStream)
                val page = pdf.getPage(0)

                val descriptor = StoredObjectDescriptor(
                    rawDescriptor.id,
                    rawDescriptor.mimeType,
                    rawDescriptor.length,
                    Bounds(0, ptToMm(page.mediaBox.width), 0, ptToMm(page.mediaBox.height), 0, pdf.numberOfPages)
                )
                objectStore.store(pdfStream, descriptor)

                //return
                descriptor
            }
            MimeType.OBJ -> {
                val objStream = objectStore.get(rawDescriptor.id)!!.inputStream()
                val obj = ObjReader.read(objStream)
                val bounds = ObjUtil.computeBounds(obj)

                val descriptor = StoredObjectDescriptor(
                    rawDescriptor.id,
                    rawDescriptor.mimeType,
                    rawDescriptor.length,
                    bounds
                )
                objectStore.store(objStream, descriptor)

                //return
                descriptor
            }

            MimeType.OCTET_STREAM -> rawDescriptor
        }
    }

    private fun ptToMm(pt: Float): Float {
        return pt * 25.4f / 72;
    }
}