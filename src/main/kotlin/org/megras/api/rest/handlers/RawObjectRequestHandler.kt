package org.megras.api.rest.handlers

import io.javalin.http.Context
import io.javalin.openapi.*
import org.megras.api.rest.GetRequestHandler
import org.megras.api.rest.RestErrorStatus
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.fs.StoredObjectId
import org.megras.data.mime.MimeType

class RawObjectRequestHandler(private val objectStore: FileSystemObjectStore) : GetRequestHandler {

    @OpenApi(
        path = "/raw/{objectId}",
        methods = [HttpMethod.GET],
        summary = "Serves the raw bytes of an object",
        tags = ["Object Store"],
        pathParams = [
            OpenApiParam(name = "objectId", type = String::class, description = "The ID of the object to retrieve.")
        ],
        responses = [
            OpenApiResponse(status = "200", description = "Successfully serves the raw object bytes. The Content-Type header will reflect the object's MIME type.", content = [OpenApiContent(type = "*/*")]),
            OpenApiResponse(status = "403", description = "Invalid objectId provided."),
            OpenApiResponse(status = "404", description = "Object not found.")
        ]
    )
    override fun get(ctx: Context) {

        val id = StoredObjectId.of(ctx.pathParam("objectId")) ?: throw RestErrorStatus(403, "invalid id")

        streamObject(id, objectStore, ctx)

    }

    companion object{
        fun streamObject(id: StoredObjectId, objectStore: FileSystemObjectStore, ctx: Context) {
            val result = objectStore.get(id) ?: throw RestErrorStatus.notFound
            ctx.header("Cache-Control", "max-age=31622400")
            val contentType = if (result.descriptor.mimeType == MimeType.TEXT) {
                "text/plain; charset=utf-8"
            } else {
                result.descriptor.mimeType.mimeString
            }
            ctx.writeSeekableStream(result.inputStream(), contentType)

        }
    }

}