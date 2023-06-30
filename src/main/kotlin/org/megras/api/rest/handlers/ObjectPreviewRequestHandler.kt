package org.megras.api.rest.handlers

import com.github.kokorin.jaffree.StreamType
import com.github.kokorin.jaffree.ffmpeg.*
import com.sksamuel.scrimage.ImmutableImage
import io.javalin.http.Context
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.apache.commons.io.IOUtils
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
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.*
import java.util.concurrent.TimeUnit
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
            MediaType.TEXT -> {
                val text = IOUtils.toString(objectStoreResult.inputStream())

                val image = BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB)
                val g = image.createGraphics()
                g.color = Color.BLACK

                var yOffset = 20
                for (line in text.split("\n")) {
                    g.drawString(line, 10, yOffset)
                    yOffset += g.fontMetrics.height
                }
                g.dispose()

                val inStream = storeImagePreview(objectId, image, objectStoreResult)

                ctx.header("Cache-Control", "max-age=31622400")
                ctx.writeSeekableStream(inStream, MimeType.PNG.mimeString)
            }

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
                val stream = objectStoreResult.byteChannel()

                val out = ByteArrayOutputStream()
                FFmpeg.atPath()
                    .addInput(ChannelInput.fromChannel(stream).setDuration(5, TimeUnit.SECONDS))
                    .setComplexFilter("compand,showwavespic=colors=white|white:size=200x200")
                    .setOverwriteOutput(true)
                    .addOutput(FrameOutput
                        .withConsumer(
                            object : FrameConsumer {
                                override fun consumeStreams(streams: List<Stream?>?) {}

                                override fun consume(frame: Frame?) {
                                    if (frame == null) {
                                        return
                                    }
                                    try {
                                        ImageIO.write(frame.image, "png", out)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            }
                        )
                        .setFrameCount(StreamType.AUDIO, 1)
                        .disableStream(StreamType.AUDIO)
                    )
                    .execute()

                val byteData = out.toByteArray()
                val imageStream = ByteArrayInputStream(byteData)
                val waveForm = ImageIO.read(imageStream)
                val image = BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB)
                val g = image.createGraphics()
                g.color = Color.WHITE
                g.fillRect(0, 0, 200, 100)
                g.drawImage(waveForm, 0, -50, null)
                g.dispose()

                val inStream = storeImagePreview(objectId, image, objectStoreResult)
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