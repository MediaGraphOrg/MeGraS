package org.megras.api.rest.handlers

import io.javalin.http.Context
import org.megras.api.rest.GetRequestHandler
import org.megras.api.rest.RestErrorStatus
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.fs.ObjectStoreResult
import org.megras.data.fs.StoredObjectDescriptor
import org.megras.data.fs.StoredObjectId
import org.megras.data.graph.LocalQuadValue
import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.data.graph.StringValue
import org.megras.data.model.MediaType
import org.megras.data.schema.MeGraS
import org.megras.data.schema.SchemaOrg
import org.megras.graphstore.MutableQuadSet
import org.megras.graphstore.QuadSet
import org.megras.id.ObjectId
import org.megras.segmentation.SegmentationUtil
import org.megras.segmentation.media.*
import org.megras.segmentation.type.PreprocessSegmentation
import org.megras.segmentation.type.RelativeSegmentation
import org.megras.segmentation.type.Segmentation
import org.megras.util.HashUtil
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream

class CanonicalSegmentRequestHandler(private val quads: MutableQuadSet, private val objectStore: FileSystemObjectStore) : GetRequestHandler {

    private val logger = LoggerFactory.getLogger(this.javaClass)

    /**
     * /{objectId}/segment/{segmentation}/{segmentDefinition}"
     */
    override fun get(ctx: Context) {

        val objectId = ctx.pathParam("objectId")
        val segmentId = ctx.pathParamMap()["segmentId"]
        val documentId = objectId + (if (segmentId != null) {"/c/$segmentId"} else {""})
        val segmentType = ctx.pathParam("segmentation")
        val segmentDefinition = ctx.pathParam("segmentDefinition")
        var nextSegmentation: Segmentation? = null
        val tail = ctx.pathParamMap()["tail"]

        val lookInCache = ctx.queryParam("nocache") == null

        val storedObject = getStoredObjectInCache(documentId)

        var segmentation = SegmentationUtil.parseSegmentation(segmentType, segmentDefinition) ?: throw RestErrorStatus.invalidSegmentation
        val currentPaths = mutableListOf("$documentId/${segmentation.toURI()}")

        if (segmentation is PreprocessSegmentation) {
            segmentation = segmentation.preprocess(storedObject.descriptor.bounds) ?: throw RestErrorStatus.invalidSegmentation
            currentPaths.add("$documentId/${segmentation.toURI()}")
        }

        if (segmentation is RelativeSegmentation && segmentation.isRelative) {
            segmentation = segmentation.toAbsolute(storedObject.descriptor.bounds) ?: throw RestErrorStatus.invalidSegmentation
            currentPaths.add("$documentId/${segmentation.toURI()}")
        }

        // check for an additional segmentation
        if (ctx.pathParamMap().containsKey("nextSegmentation")) {
            val nextSegmentType = ctx.pathParam("nextSegmentation")
            val nextSegmentDefinition = ctx.pathParam("nextSegmentDefinition")

            nextSegmentation = SegmentationUtil.parseSegmentation(nextSegmentType, nextSegmentDefinition) ?: throw RestErrorStatus.invalidSegmentation

            if (nextSegmentation is PreprocessSegmentation) {
                nextSegmentation = nextSegmentation.preprocess(storedObject.descriptor.bounds) ?: throw RestErrorStatus.invalidSegmentation
            }

            if (nextSegmentation is RelativeSegmentation && nextSegmentation.isRelative) {
                nextSegmentation = nextSegmentation.toAbsolute(storedObject.descriptor.bounds) ?: throw RestErrorStatus.invalidSegmentation
            }

            // if two segmentations are orthogonal, there can be no interaction between them
            if (segmentation.orthogonalTo(nextSegmentation)) {
                // reorder based on the segmentation types
                if (SegmentationUtil.shouldSwap(segmentation.segmentationType, nextSegmentation.segmentationType)) {
                    ctx.redirect("/$documentId/${nextSegmentation.toURI()}/${segmentation.toURI()}" + (if (tail != null) "/$tail" else ""))
                    return
                }
            } else {
                nextSegmentation = nextSegmentation.translate(segmentation.bounds)

                // if two segmentations are equivalent, discard the second one
                if (segmentation.equivalentTo(nextSegmentation)) {
                    ctx.redirect("/${currentPaths.first()}" + (if (tail != null) "/$tail" else ""))
                    return
                }

                // if the first segmentation contains the second one, directly apply the second one
                if (segmentation.contains(nextSegmentation)) {
                    ctx.redirect("/$documentId/$nextSegmentation" + (if (tail != null) "/$tail" else ""))
                    return
                }
            }
        }

        if (lookInCache) {
            // check for exact path matches
            var redirectPath = findPathInCache(currentPaths)
            if (redirectPath != null) {
                redirect(ctx, redirectPath, nextSegmentation)
                logger.info("found ${currentPaths.first()} in cache: $redirectPath")
                return
            }

            // check for equivalent segmentations
            redirectPath = findEquivalentInCache(objectId, segmentation, currentPaths)
            if (redirectPath != null) {
                redirect(ctx, redirectPath, nextSegmentation)
                logger.info("found equivalent to ${currentPaths.first()} in cache: $redirectPath")
                return
            }
        }

        if (!segmentation.bounds.overlaps(storedObject.descriptor.bounds)) {
            throw RestErrorStatus.emptySegment
        }

        if (segmentation.contains(storedObject.descriptor.bounds)) {
            currentPaths.forEach { currentPath ->
                quads.add(Quad(LocalQuadValue(currentPath), SchemaOrg.SAME_AS.uri, LocalQuadValue(documentId)))
            }
            redirect(ctx, LocalQuadValue(documentId).uri, nextSegmentation)
            return
        }

        if (segmentId != null) {
            val relevant = quads.filterSubject(LocalQuadValue(documentId))
            if (relevant.size > 0) {
                val previousSegmentation = getSegmentationForCached(relevant, LocalQuadValue(documentId))
                if (previousSegmentation != null && !previousSegmentation.orthogonalTo(segmentation)) {

                    // if this segmentation is equivalent to previous, skip and redirect to it
                    if (previousSegmentation.equivalentTo(segmentation.translate(previousSegmentation.bounds))) {
                        currentPaths.forEach { currentPath ->
                            quads.add(Quad(LocalQuadValue(currentPath), SchemaOrg.SAME_AS.uri, LocalQuadValue(documentId)))
                        }
                        redirect(ctx, LocalQuadValue(objectId).uri, nextSegmentation)
                        return
                    }
                }
            }
        }

        val segmentResult: SegmentationResult = when(MediaType.mimeTypeMap[storedObject.descriptor.mimeType]) {
            MediaType.TEXT -> TextSegmenter.segment(storedObject.inputStream(), segmentation)
            MediaType.IMAGE -> ImageSegmenter.segment(storedObject.inputStream(), segmentation)
            MediaType.AUDIO,
            MediaType.VIDEO -> AudioVideoSegmenter.segment(storedObject, segmentation)
            MediaType.DOCUMENT -> DocumentSegmenter.segment(storedObject.inputStream(), segmentation)
            MediaType.MESH -> MeshSegmenter.segment(storedObject.inputStream(), segmentation)
            MediaType.UNKNOWN, null -> throw RestErrorStatus.unknownMediaType
        } ?: throw RestErrorStatus.invalidSegmentation

        val inStream = ByteArrayInputStream(segmentResult.segment)

        val cachedObjectId = objectStore.idFromStream(inStream)
        val descriptor = StoredObjectDescriptor(
            cachedObjectId,
            storedObject.descriptor.mimeType,
            segmentResult.segment.size.toLong(),
            segmentResult.bounds
        )

        inStream.reset()
        objectStore.store(inStream, descriptor)

        val cacheId = HashUtil.hashToBase64(documentId + segmentation.toURI(), HashUtil.HashType.MD5)
        val cacheObject = LocalQuadValue("$objectId/c/$cacheId")

        quads.addAll(
            listOf(
                Quad(cacheObject, MeGraS.CANONICAL_ID.uri, StringValue(descriptor.id.id)),
                Quad(cacheObject, MeGraS.BOUNDS.uri, StringValue(segmentResult.bounds.toString())), // bounds of the resulting medium
                Quad(cacheObject, MeGraS.SEGMENT_OF.uri, ObjectId(documentId)),
                Quad(cacheObject, MeGraS.SEGMENT_TYPE.uri, StringValue(segmentation.getType())),
                Quad(cacheObject, MeGraS.SEGMENT_DEFINITION.uri, StringValue(segmentation.getDefinition())),
                Quad(cacheObject, MeGraS.SEGMENT_BOUNDS.uri, StringValue(segmentation.bounds.toString())), // bounds of the segmentation
            )
        )
        currentPaths.forEach { currentPath ->
            quads.add(Quad(LocalQuadValue(currentPath), SchemaOrg.SAME_AS.uri, cacheObject))
        }

        redirect(ctx, cacheObject.uri, nextSegmentation)
    }

    private fun findPathInCache(currentPaths: List<String>): String? {
        currentPaths.forEach { currentPath ->
            quads.filter(listOf(LocalQuadValue(currentPath)), listOf(SchemaOrg.SAME_AS.uri), null).forEach {
                val cached = it.`object` as LocalQuadValue
                return cached.uri
            }
        }
        return null
    }

    private fun findEquivalentInCache(objectId: String, segmentation: Segmentation, currentPaths: List<String>): String? {
        quads.filter(
            null, listOf(MeGraS.SEGMENT_OF.uri), listOf(ObjectId(objectId))
        ).forEach { potentialMatch ->
            // go through all segments of the medium and check their bounds
            quads.filter(
                listOf(potentialMatch.subject), listOf(MeGraS.SEGMENT_BOUNDS.uri), listOf(StringValue(segmentation.bounds.toString()))
            ).forEach { _ ->
                // if the bounds match, check the segmentation
                val potentialMatchSegmentation = getSegmentationForCached(quads, potentialMatch.subject) ?: return@forEach

                if (segmentation.equivalentTo(potentialMatchSegmentation)) {
                    val cached = potentialMatch.subject as LocalQuadValue
                    currentPaths.forEach { currentPath ->
                        quads.add(Quad(LocalQuadValue(currentPath), SchemaOrg.SAME_AS.uri, cached))
                    }
                    return cached.uri
                }
            }
        }
        return null
    }

    private fun getSegmentationForCached(quads: QuadSet, cacheObject: QuadValue): Segmentation? {
        val segmentTypeQuad = quads.filter(listOf(cacheObject), listOf(MeGraS.SEGMENT_TYPE.uri), null).first()
        val potentialMatchType = segmentTypeQuad.`object` as StringValue
        val segmentDefinitionQuad = quads.filter(listOf(cacheObject), listOf(MeGraS.SEGMENT_DEFINITION.uri), null).first()
        val potentialMatchDefinition = segmentDefinitionQuad.`object` as StringValue
        return SegmentationUtil.parseSegmentation(potentialMatchType.value, potentialMatchDefinition.value)
    }

    private fun getStoredObjectInCache(documentId: String): ObjectStoreResult {
        val canonicalId = quads.filter(
            setOf(ObjectId(documentId)),
            setOf(MeGraS.CANONICAL_ID.uri),
            null
        ).firstOrNull()?.`object` as? StringValue ?: throw RestErrorStatus.notFound
        val osId = StoredObjectId.of(canonicalId.value) ?: throw RestErrorStatus.notFound
        return objectStore.get(osId) ?: throw RestErrorStatus.notFound
    }

    private fun redirect(ctx: Context, path: String, nextSegmentation: Segmentation?) {
        if (nextSegmentation != null) {
            ctx.redirect("/$path/${nextSegmentation.toURI()}")
        } else {
            ctx.redirect("/$path")
        }
    }
}