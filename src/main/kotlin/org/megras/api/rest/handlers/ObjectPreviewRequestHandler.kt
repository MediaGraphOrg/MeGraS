package org.megras.api.rest.handlers

import com.github.kokorin.jaffree.StreamType
import com.github.kokorin.jaffree.ffmpeg.ChannelInput
import com.github.kokorin.jaffree.ffmpeg.ChannelOutput
import com.github.kokorin.jaffree.ffmpeg.FFmpeg
import com.github.kokorin.jaffree.ffmpeg.NullOutput
import com.sksamuel.scrimage.ImmutableImage
import io.javalin.http.Context
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.megras.api.rest.GetRequestHandler
import org.megras.api.rest.RestErrorStatus
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.fs.ObjectStoreResult
import org.megras.data.fs.StoredObjectDescriptor
import org.megras.data.fs.StoredObjectId
import org.megras.data.graph.Quad
import org.megras.data.graph.StringValue
import org.megras.data.mime.MimeType
import org.megras.data.model.MediaType
import org.megras.data.schema.MeGraS
import org.megras.graphstore.MutableQuadSet
import org.megras.id.ObjectId
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO

class ObjectPreviewRequestHandler(private val quads: MutableQuadSet, private val objectStore: FileSystemObjectStore) : GetRequestHandler {
    override fun get(ctx: Context) {

        val objectId = ObjectId(ctx.pathParam("objectId"))

        val previewId = quads.filter(
            setOf(objectId),
            setOf(MeGraS.PREVIEW_ID.uri),
            null
        ).firstOrNull()?.`object` as? StringValue

        if (previewId != null) { //preview present
            val osId = StoredObjectId.of(previewId.value) ?: throw RestErrorStatus.notFound
            RawObjectRequestHandler.streamObject(osId, objectStore, ctx)
            return
        }

        //preview not present
        val rawId = quads.filter(
            setOf(objectId),
            setOf(MeGraS.CANONICAL_ID.uri),
            null
        ).firstOrNull()?.`object` as? StringValue ?: throw RestErrorStatus.notFound

        val osId = StoredObjectId.of(rawId.value) ?: throw RestErrorStatus.notFound
        val objectStoreResult = objectStore.get(osId) ?: throw RestErrorStatus.notFound

        val mediaType = MediaType.mimeTypeMap[objectStoreResult.descriptor.mimeType] ?: MediaType.UNKNOWN

        when(mediaType) {
            MediaType.TEXT -> TODO()

            MediaType.IMAGE -> {

                val buffered = ImmutableImage.loader().fromStream(objectStoreResult.inputStream()).max(200, 200).toNewBufferedImage(BufferedImage.TYPE_INT_ARGB) //TODO select appropriate type based on input image

                val inStream = storeImagePreview(objectId, buffered, objectStoreResult)

                ctx.header("Cache-Control", "max-age=31622400")
                ctx.writeSeekableStream(inStream, MimeType.PNG.mimeString)
            }

            MediaType.VIDEO -> {
                val stream = objectStoreResult.byteChannel()

                val totalDuration = AtomicLong()
                FFmpeg.atPath()
                    .addInput(ChannelInput.fromChannel(stream))
                    .addOutput(NullOutput())
                    .setProgressListener { progress -> totalDuration.set(progress.timeMillis) }
                    .execute()

                val out = SeekableInMemoryByteChannel()
                FFmpeg.atPath()
                    .addInput(ChannelInput.fromChannel(stream).setPosition(totalDuration.get() / 2))
                    .setFilter(StreamType.VIDEO, "scale=200:200:force_original_aspect_ratio=decrease")
                    .addArguments("-vframes", "1")
                    .setOverwriteOutput(true)
                    .addOutput(ChannelOutput.toChannel("thumbnail.png", out))
                    .execute()

                val playIcon = ImageIO.read(File("play_icon.png"))
                val imageStream = ByteArrayInputStream(out.array())
                val image = ImageIO.read(imageStream)
                val g = image.createGraphics()
                val w = (image.width - 100) / 2
                val h = (image.height - 100) / 2
                g.drawImage(playIcon, w, h, null)
                g.dispose()

                val inStream = storeImagePreview(objectId, image, objectStoreResult)

                ctx.header("Cache-Control", "max-age=31622400")
                ctx.writeSeekableStream(inStream, MimeType.PNG.mimeString)
            }

            MediaType.AUDIO -> {
                val audioIcon = ImageIO.read(File("audio_icon.png"))

                val inStream = storeImagePreview(objectId, audioIcon, objectStoreResult)
                ctx.header("Cache-Control", "max-age=31622400")
                ctx.writeSeekableStream(inStream, MimeType.PNG.mimeString)
            }

            MediaType.DOCUMENT -> {
                val pdfStream = objectStoreResult.inputStream()
                val pdf = PDDocument.load(pdfStream)
                val pdfRenderer = PDFRenderer(pdf)
                val page = pdfRenderer.renderImage(0, 1F, ImageType.ARGB)

                val pageStream = ByteArrayOutputStream()
                ImageIO.write(page, "PNG", pageStream)

                val buffered = ImmutableImage.loader().fromBytes(pageStream.toByteArray()).max(200, 200).toNewBufferedImage(BufferedImage.TYPE_INT_ARGB)
                val inStream = storeImagePreview(objectId, buffered, objectStoreResult)

                ctx.header("Cache-Control", "max-age=31622400")
                ctx.writeSeekableStream(inStream, MimeType.PNG.mimeString)
            }

            MediaType.UNKNOWN -> TODO()
            else -> TODO()
        }
    }

    private fun storeImagePreview(objectId: ObjectId, bufferedImage: BufferedImage, objectStoreResult: ObjectStoreResult): InputStream {
        val outStream = ByteArrayOutputStream()
        ImageIO.write(bufferedImage, "PNG", outStream)

        return storeImagePreview(objectId, outStream.toByteArray(), objectStoreResult)
    }

    private fun storeImagePreview(objectId: ObjectId, buffer: ByteArray, objectStoreResult: ObjectStoreResult): InputStream {
        val inStream = ByteArrayInputStream(buffer)

        val id = objectStore.idFromStream(inStream)

        inStream.reset()

        val descriptor = StoredObjectDescriptor(
            id,
            MimeType.PNG,
            buffer.size.toLong(),
            objectStoreResult.descriptor.bounds
        )

        objectStore.store(inStream, descriptor)

        quads.add(Quad(objectId, MeGraS.PREVIEW_ID.uri, StringValue(id.id)))

        inStream.reset()
        return inStream
    }

}