package org.megras.api.rest.handlers

import io.javalin.http.Context
import io.javalin.openapi.*
import org.megras.api.rest.GetRequestHandler
import org.megras.api.rest.RestErrorStatus
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.fs.StoredObjectId
import org.megras.data.graph.StringValue
import org.megras.data.schema.MeGraS
import org.megras.graphstore.QuadSet
import org.megras.id.ObjectId

class CanonicalObjectRequestHandler(private val quads: QuadSet, private val objectStore: FileSystemObjectStore) : GetRequestHandler {

    @OpenApi(
        path = "/{objectId}",
        methods = [HttpMethod.GET],
        summary = "Serves the canonical representation of an object",
        tags = ["Object Store"],
        pathParams = [
            OpenApiParam(name = "objectId", type = String::class, description = "The ID of the object to retrieve.")
        ],
        responses = [
            OpenApiResponse(status = "200", description = "Successfully serves the canonical object. The Content-Type header will reflect the object's MIME type.", content = [OpenApiContent(type = "*/*")]),
            OpenApiResponse(status = "404", description = "Object or canonical ID not found.")
        ]
    )
    override fun get(ctx: Context) {

        val canonicalId = quads.filter(
            setOf(ObjectId(ctx.pathParam("objectId"))),
            setOf(MeGraS.CANONICAL_ID.uri),
            null
        ).firstOrNull()?.`object` as? StringValue ?: throw RestErrorStatus.notFound

        val osId = StoredObjectId.of(canonicalId.value) ?: throw RestErrorStatus.notFound

        RawObjectRequestHandler.streamObject(osId, objectStore, ctx)

    }


}