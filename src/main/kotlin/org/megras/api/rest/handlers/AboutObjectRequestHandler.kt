package org.megras.api.rest.handlers

import io.javalin.http.ContentType
import io.javalin.http.Context
import org.megras.api.rest.GetRequestHandler
import org.megras.api.rest.RestErrorStatus
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.graph.QuadValue
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
        val css = """
            <style>
                body {
                    font-family: 'Segoe UI', Arial, sans-serif;
                    line-height: 1.6;
                    max-width: 1400px;
                    margin: 0 auto;
                    padding: 20px;
                    background-color: #f5f5f5;
                    color: #333;
                }
                h1 {
                    color: #2c3e50;
                    padding-bottom: 10px;
                    border-bottom: 1px solid #ddd;
                    text-align: center;
                }
                .media-container {
                    margin: 20px auto;
                    text-align: center;
                    background: white;
                    padding: 15px;
                    border-radius: 8px;
                    box-shadow: 0 2px 8px rgba(0,0,0,0.15);
                    max-width: 80%;
                }
                img, video, audio, embed {
                    max-width: 100%;
                    max-height: 600px;
                    border-radius: 4px;
                }
                a {
                    color: black;
                    text-decoration: none;
                    transition: color 0.2s;
                    white-space: nowrap;
                    overflow: hidden;
                    text-overflow: ellipsis;
                }
                a:hover {
                    color: black;
                    text-decoration: underline;
                }
                a:visited {
                    color: black;
                }
                table {
                    width: 100%;
                    border-collapse: collapse;
                    margin: 30px 0;
                    background-color: white;
                    box-shadow: 0 3px 10px rgba(0,0,0,0.1);
                    border-radius: 8px;
                    overflow: hidden;
                    table-layout: fixed;
                }
                th, td {
                    padding: 14px 16px;
                    border-bottom: 1px solid #e0e0e0;
                    white-space: nowrap;
                    overflow: hidden;
                    text-overflow: ellipsis;
                }
                th {
                    background-color: #2c3e50;
                    color: white;
                    text-align: left;
                    position: sticky;
                    top: 0;
                }
                tr:last-child td {
                    border-bottom: none;
                }
                tr:hover {
                    background-color: #f9f9f9;
                }
                .vector-value {
                    font-family: monospace;
                    background-color: #f0f0f0;
                    padding: 2px 4px;
                    border-radius: 3px;
                    cursor: help;
                    display: inline-block;
                    max-width: 100%;
                    overflow: hidden;
                    text-overflow: ellipsis;
                }
            </style>
        """.trimIndent()

        buf.append(
          """
              <!DOCTYPE html>
              <head>
                  <title>About '$objectId'</title>
                  ${css}
              </head>
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
                buf.append("<div class='media-container'><img src='${objectId.toPath()}' alt='Image preview'/></div>")
            }

            MediaType.VIDEO.name -> {
                buf.append(
                    """
                    <div class='media-container'>
                        <video controls>
                          <source src='${objectId.toPath()}' type='${mimeType?.value}'>
                          Your browser does not support the video tag.
                        </video>
                    </div>
                """.trimIndent()
                )
            }

            MediaType.AUDIO.name -> {
                buf.append(
                    """
                    <div class='media-container'>
                        <audio controls>
                          <source src='${objectId.toPath()}' type='${mimeType?.value}'>
                          Your browser does not support the audio tag.
                        </audio>
                    </div>
                """.trimIndent()
                )
            }

            MimeType.TEXT.name -> {
                buf.append("<div class='media-container'><embed src='${objectId.toPath()}'></div>")
            }

            else -> {/* no preview */
            }
        }

        // construct a list of all the relevant triples in a html table
        // Make the link clickable, only if it is a URI
        // Ensure that the URIs are displayed correctly
        // e.g., <http://localhost:8080/ig4eHDw8PBwehl44EMsGGVowgwnovvt3-tTGOdJd4baxnIRMdrTy6sg> <http://megras.org/schema#canonicalMimeType> image/png^^String
        buf.append("\n<br><table>\n")
        buf.append("<tr><th>Subject</th><th>Predicate</th><th>Object</th></tr>\n")
        relevant.sortedBy { it.subject.toString().length }.forEach {
            buf.append("<tr>")
            buf.append("<td>${it.subject.toHtml()}</td>")
            buf.append("<td>${it.predicate.toHtml()}</td>")
            buf.append("<td>${it.`object`.toHtml()}</td>")
            buf.append("</tr>\n")
        }
        buf.append("</table>\n")


        /*buf.append("\n<br><textarea readonly style='width: 100%; min-height: 500px; resize: vertical;'>\n")
        relevant.sortedBy { it.subject.toString().length }.forEach {
            buf.append("${it.subject} ${it.predicate} ${it.`object`}\n")
        }
        buf.append("</textarea>")*/

        buf.append("""
            </body>
            </html>
        """.trimIndent())

        ctx.contentType(ContentType.TEXT_HTML.mimeType)
        ctx.result(buf.toString())
    }
}

// TODO: move this to a common place
private fun QuadValue.toHtml(): String {
    // if it is a URI, make it clickable
    // if it is a literal, return the value
    // if it is a vector, return the value as a string and add a tooltip with the full value
    return when (this) {
        is org.megras.data.graph.URIValue -> {
            // Make URI values clickable by replacing angle brackets with HTML entities
            // and wrapping them in an anchor tag
            val displayValue = toString().replace("<", "&lt;").replace(">", "&gt;")
            "<a href='$value/about'>$displayValue</a>"
        }
        is org.megras.data.graph.VectorValue -> {
            // For vector values, show them in a more readable form with a tooltip
            val shortDisplay = if (length > 8) {
                "[${toString().substring(1, 50)}...]"
            } else {
                toString()
            }
            "<span title='${toString()}'>$shortDisplay</span>"
        }
        else -> {
            // For other types (StringValue, DoubleValue, etc.), just return string representation
            toString()
        }
    }
}
