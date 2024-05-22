package org.megras.api.rest.handlers

import io.javalin.http.ContentType
import io.javalin.http.Context
import org.megras.api.rest.GetRequestHandler
import org.megras.api.rest.RestErrorStatus
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.graph.StringValue
import org.megras.data.mime.MimeType
import org.megras.data.model.MediaType
import org.megras.data.schema.MeGraS
import org.megras.graphstore.QuadSet
import org.megras.id.ObjectId

class AboutObjectRequestHandler(private val quads: QuadSet, private val objectStore: FileSystemObjectStore) : GetRequestHandler {

    override fun get(ctx: Context) {

        val objectId = ObjectId(ctx.pathParam("objectId"))

        var relevant = quads.filter(setOf(objectId), null,null) + quads.filter(null, null, setOf(objectId))

        if (relevant.isEmpty()) {
            throw RestErrorStatus.notFound
        }

        val buf = StringBuilder()

        buf.append(
          """
              <!DOCTYPE html>
              <html><head><title>About '$objectId'</title></head>
              <body>
              
          """.trimIndent()
        )

        var parent = quads.filter(setOf(objectId), setOf(MeGraS.SEGMENT_OF.uri), null).firstOrNull()?.`object`
        while (parent != null) {
            relevant += quads.filter(setOf(parent), null, null)
            parent = quads.filter(setOf(parent), setOf(MeGraS.SEGMENT_OF.uri), null).firstOrNull()?.`object`
        }

        val mediaType = relevant.filterPredicate(MeGraS.MEDIA_TYPE.uri).firstOrNull()?.`object` as? StringValue
        val mimeType = relevant.filterPredicate(MeGraS.CANONICAL_MIME_TYPE.uri).firstOrNull()?.`object` as? StringValue

        when(mediaType?.value) {
            MediaType.IMAGE.name -> {
                buf.append("<img src='${objectId.toPath()}'/>")
            }

            MediaType.VIDEO.name -> {
                buf.append("""
                    <video controls>
                      <source src='${objectId.toPath()}' type='${mimeType?.value}'>
                      Your browser does not support the video tag.
                    </video>
                """.trimIndent())
            }

            MediaType.AUDIO.name -> {
                buf.append("""
                    <audio controls>
                      <source src='${objectId.toPath()}' type='${mimeType?.value}'>
                      Your browser does not support the video tag.
                    </audio>
                """.trimIndent())
            }

            MimeType.TEXT.name -> {
                buf.append("<embed src='${objectId.toPath()}'>")
            }

            else -> {/* no preview */}
        }

        buf.append("\n<br><textarea readonly style='width: 100%; min-height: 500px; resize: vertical;'>\n")
        relevant.sortedBy { it.subject.toString().length }.forEach {
            buf.append("${it.subject} ${it.predicate} ${it.`object`}\n")
        }

        buf.append("""
            </textarea>
            </body>
            </html>
        """.trimIndent())

        ctx.contentType(ContentType.TEXT_HTML.mimeType)
        ctx.result(buf.toString())
    }
}