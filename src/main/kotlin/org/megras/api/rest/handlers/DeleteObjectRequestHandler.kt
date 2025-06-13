package org.megras.api.rest.handlers

import io.javalin.http.Context
import org.megras.api.rest.DeleteRequestHandler
import org.megras.api.rest.RestErrorStatus
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.fs.StoredObjectId
import org.megras.data.graph.QuadValue
import org.megras.data.graph.StringValue
import org.megras.data.schema.MeGraS
import org.megras.data.schema.SchemaOrg
import org.megras.graphstore.MutableQuadSet
import org.megras.graphstore.QuadSet
import org.megras.id.ObjectId
import org.slf4j.LoggerFactory
import io.javalin.openapi.*

class DeleteObjectRequestHandler(private val quads: MutableQuadSet, private val objectStore: FileSystemObjectStore) :
    DeleteRequestHandler {

    private val logger = LoggerFactory.getLogger(this.javaClass)

    @OpenApi(
        path = "/{objectId}",
        methods = [HttpMethod.DELETE],
        summary = "Deletes an object and all its associated data.",
        description = "Permanently deletes a specified object. This includes the object itself, all its segments, any associated metadata (quads in the graph store), and all related stored files (such as raw versions, canonical representations, and previews).",
        tags = ["Object Management"],
        pathParams = [
            OpenApiParam(name = "objectId", type = String::class, description = "The ID of the object to be deleted.")
        ],
        responses = [
            OpenApiResponse(status = "200", description = "Object and all associated data successfully deleted."),
            OpenApiResponse(status = "404", description = "Object not found or specified ID is invalid.")
        ]
    )
    override fun delete(ctx: Context) {

        val objectId = ObjectId(ctx.pathParam("objectId"))

        var relevant = recursiveSearch(objectId)

        val uris = relevant.map { it.subject }.toSet()
        relevant += quads.filter(null, setOf(SchemaOrg.SAME_AS.uri), uris)

        val fileIds = relevant.filter(
            null,
            setOf(MeGraS.CANONICAL_ID.uri, MeGraS.RAW_ID.uri, MeGraS.PREVIEW_ID.uri),
            null)
            .map { StoredObjectId.of((it.`object` as StringValue).value) ?: throw RestErrorStatus.notFound }

        if (quads.removeAll(relevant) && objectStore.removeAll(fileIds)) {
            logger.info("deleted ${relevant.size} quads and ${fileIds.size} files")
        }
    }

    /**
     * Recursively finds all segments of the starting object id
     */
    private fun recursiveSearch(id: QuadValue): QuadSet {

        var relevant = quads.filter(setOf(id), null,null)
        val children = quads.filter(null, setOf(MeGraS.SEGMENT_OF.uri), setOf(id)).map { it.subject }

        children.forEach { child ->
            relevant += recursiveSearch(child)
        }

        return relevant
    }
}