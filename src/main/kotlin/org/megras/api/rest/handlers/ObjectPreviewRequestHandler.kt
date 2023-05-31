package org.megras.api.rest.handlers

import com.sksamuel.scrimage.ImmutableImage
import io.javalin.http.Context
import org.megras.api.rest.GetRequestHandler
import org.megras.api.rest.RestErrorStatus
import org.megras.data.fs.FileSystemObjectStore
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

                val outStream = ByteArrayOutputStream()
                ImageIO.write(buffered, "PNG", outStream)

                val buf = outStream.toByteArray()

                val inStream = ByteArrayInputStream(buf)

                val id = objectStore.idFromStream(inStream)

                inStream.reset()

                val descriptor = StoredObjectDescriptor(
                    id,
                    MimeType.PNG,
                    buf.size.toLong(),
                    objectStoreResult.descriptor.bounds
                )

                objectStore.store(inStream, descriptor)

                quads.add(Quad(objectId, MeGraS.PREVIEW_ID.uri, StringValue(id.id)))

                inStream.reset()

                ctx.header("Cache-Control", "max-age=31622400")
                ctx.writeSeekableStream(inStream, MimeType.PNG.mimeString)

            }
            MediaType.AUDIO -> TODO()
            MediaType.UNKNOWN -> TODO()
            else -> TODO()
        }


    }


}