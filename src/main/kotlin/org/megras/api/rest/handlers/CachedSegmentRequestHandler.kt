package org.megras.api.rest.handlers

import io.javalin.http.Context
import io.javalin.openapi.OpenApi
import io.javalin.openapi.OpenApiResponse
import org.megras.api.rest.GetRequestHandler
import org.megras.api.rest.RestErrorStatus
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.fs.StoredObjectId
import org.megras.data.graph.LocalQuadValue
import org.megras.data.graph.StringValue
import org.megras.data.schema.MeGraS
import org.megras.graphstore.QuadSet

class CachedSegmentRequestHandler(private val quads: QuadSet, private val objectStore: FileSystemObjectStore) : GetRequestHandler {

    @OpenApi(
        summary = "Get a cached segment by its ID",
        path = "/{objectId}/c/{segmentId}",
        tags = ["Object Segmentation"],
        description = "Returns the cached segment for the given object ID and segment ID.",
        responses = [
            OpenApiResponse("200", description = "Cached segment data"),
            OpenApiResponse("404", description = "Segment not found")
        ]
    )
    // /{objectId}/c/{segmentId}
    override fun get(ctx: Context) {

        val canonicalId = quads.filter(
            setOf(LocalQuadValue(ctx.path().replaceFirst("/", ""))),
            setOf(MeGraS.CANONICAL_ID.uri),
            null
        ).firstOrNull()?.`object` as? StringValue ?: throw RestErrorStatus.notFound

        val osId = StoredObjectId.of(canonicalId.value) ?: throw RestErrorStatus.notFound

        RawObjectRequestHandler.streamObject(osId, objectStore, ctx)
    }
}