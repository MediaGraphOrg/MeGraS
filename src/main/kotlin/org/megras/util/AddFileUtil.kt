package org.megras.util

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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

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
                Quad(oid, MeGraS.MIME_TYPE.uri, StringValue(descriptor.mimeType.mimeString)),
                Quad(oid, MeGraS.MEDIA_TYPE.uri, StringValue(MediaType.mimeTypeMap[descriptor.mimeType]!!.name)),
                Quad(oid, MeGraS.FILE_NAME.uri, StringValue(file.name)),
                Quad(oid, MeGraS.CANONICAL_ID.uri, StringValue(canonical.id.id))
            )
        )

        return oid
    }


    private fun generateCanonicalRepresentation(
        objectStore: FileSystemObjectStore,
        rawDescriptor: StoredObjectDescriptor
    ): StoredObjectDescriptor {

        return when (rawDescriptor.mimeType) {

            //images to keep
            MimeType.JPEG_I,
            MimeType.PNG -> rawDescriptor

            //images to transform
            MimeType.BMP,
            MimeType.GIF,
            MimeType.SVG,
            MimeType.TIFF -> {

                try {
                    val buffered = ImageIO.read(objectStore.get(rawDescriptor.id)!!.inputStream())
                    val outStream = ByteArrayOutputStream()
                    ImageIO.write(buffered, "PNG", outStream)

                    val buf = outStream.toByteArray()

                    val inStream = ByteArrayInputStream(buf)

                    val id = objectStore.idFromStream(inStream)

                    inStream.reset()

                    val descriptor = StoredObjectDescriptor(
                        id,
                        MimeType.PNG,
                        buf.size.toLong()
                    )

                    objectStore.store(inStream, descriptor)

                    //return
                    descriptor

                } catch (e: Exception) {
                    //TODO log
                    rawDescriptor
                }

            }

            //audio to keep
            MimeType.OGG,
            MimeType.MPEG_A,
            MimeType.AAC,
            MimeType.MP4_A -> rawDescriptor


            //audio to transform
            MimeType.ADP,
            MimeType.AIF,
            MimeType.AU,
            MimeType.MIDI,
            MimeType.WAV,
            MimeType.WAX,
            MimeType.WMA -> {
                TODO()
            }


            //raw and text types to keep
            MimeType.OCTET_STREAM,
            MimeType.CSS,
            MimeType.CSV,
            MimeType.HTML,
            MimeType.JS,
            MimeType.JSON,
            MimeType.TEXT -> rawDescriptor
        }

    }

}