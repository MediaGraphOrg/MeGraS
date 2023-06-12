package org.megras.api.rest.handlers

import io.javalin.http.Context
import org.megras.api.rest.DeleteRequestHandler
import org.megras.api.rest.RestErrorStatus
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.fs.StoredObjectId
import org.megras.data.graph.QuadValue
import org.megras.data.graph.StringValue
import org.megras.data.schema.MeGraS
import org.megras.graphstore.MutableQuadSet
import org.megras.graphstore.QuadSet
import org.megras.id.ObjectId
import org.slf4j.LoggerFactory

class DeleteObjectRequestHandler(private val quads: MutableQuadSet, private val objectStore: FileSystemObjectStore) :
    DeleteRequestHandler {

    private val logger = LoggerFactory.getLogger(this.javaClass)

    override fun delete(ctx: Context) {

        val objectId = ObjectId(ctx.pathParam("objectId"))

        val relevant = recursiveSearch(objectId)

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