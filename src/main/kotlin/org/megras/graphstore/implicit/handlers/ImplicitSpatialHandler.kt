package org.megras.graphstore.implicit.handlers

import org.megras.data.graph.Quad
import org.megras.data.graph.URIValue
import org.megras.data.schema.MeGraS
import org.megras.graphstore.BasicQuadSet
import org.megras.graphstore.QuadSet
import org.megras.graphstore.implicit.ImplicitRelationHandler
import org.megras.graphstore.implicit.ImplicitRelationMutableQuadSet
import org.megras.segmentation.SegmentationUtil
import org.megras.segmentation.type.Segmentation
import org.megras.util.Constants


abstract class AbstractImplicitSpatialHandler(
    relationName: String,
    private val filter: (Segmentation?, Segmentation?) -> Boolean
) : ImplicitRelationHandler {

    override val predicate: URIValue = URIValue("${Constants.SPATIAL_SEGMENT_PREFIX}/$relationName")

    protected lateinit var quadSet: ImplicitRelationMutableQuadSet

    override fun init(quadSet: ImplicitRelationMutableQuadSet) {
        this.quadSet = quadSet
    }

    private fun getSegmentation(subject: URIValue): Segmentation? {
        val segmentDefinition = quadSet.filter(
            setOf(subject),
            setOf(MeGraS.SEGMENT_DEFINITION.uri),
            null)
            .firstOrNull() ?: return null
        val segmentType = quadSet.filter(
            setOf(subject),
            setOf(MeGraS.SEGMENT_TYPE.uri),
            null)
            .firstOrNull() ?: return null
        return SegmentationUtil.parseSegmentation(segmentType.`object`.toString().removeSuffix("^^String"), segmentDefinition.`object`.toString().removeSuffix("^^String"))
    }

    private fun getParent(subject: URIValue): URIValue? {
        return quadSet.filter(
            setOf(subject),
            setOf(MeGraS.SEGMENT_OF.uri),
            null)
            .firstOrNull()?.`object` as? URIValue
    }

    private fun getSpatialCandidatesAndCaches(subject: URIValue): SpatialCandidatesResult? {
        val segment = getSegmentation(subject) ?: return null
        val parent = getParent(subject) ?: return null
        //TODO: get actual top ancestor
        //TODO: check behavior of segment of segment
        val candidates = quadSet.filter { it.subject is URIValue && it.subject != subject && parent == getParent(it.subject)}
            .map { it.subject as URIValue }
            .toSet()
        val segmentsCache = candidates.associateWith { getSegmentation(it) }
        return SpatialCandidatesResult(segment, candidates, segmentsCache)
    }

    private data class SpatialCandidatesResult(
        val segment: Segmentation?,
        val candidates: Set<URIValue>,
        val segmentsCache: Map<URIValue, Segmentation?>
    )

    override fun findObjects(subject: URIValue): Set<URIValue> {
        val (segment, candidates, segmentsCache) = getSpatialCandidatesAndCaches(subject) ?: return emptySet()
        return candidates.filter {
            filter(segment, segmentsCache[it])
        }.toSet()
    }

    override fun findSubjects(`object`: URIValue): Set<URIValue> {
        val (segment, candidates, segmentsCache) = getSpatialCandidatesAndCaches(`object`) ?: return emptySet()
        return candidates.filter {
            filter(segment, segmentsCache[it])
        }.toSet()
    }

    override fun findAll(): QuadSet {
        val subjects = quadSet.filter { it.subject is URIValue }
            .map { it.subject as URIValue }
            .toSet()
        val segmentsCache = subjects.associateWith { getSegmentation(it) }
        val pairs = mutableSetOf<Quad>()
        for (subject1 in subjects) {
            val segment1 = segmentsCache[subject1]
            for (subject2 in subjects) {
                if (subject1 != subject2) {
                    val segment2 = segmentsCache[subject2]
                    if (filter(segment1, segment2)) {
                        pairs.add(Quad(subject1, predicate, subject2))
                    }
                }
            }
        }
        return BasicQuadSet(pairs)
    }
}

class ContainsSpatialHandler : AbstractImplicitSpatialHandler(
    relationName = "contains",
    filter = { segment1, segment2 ->
        segment1 != null && segment2 != null && segment1.contains(segment2)
    }
)

class EqualsSpatialHandler : AbstractImplicitSpatialHandler(
    relationName = "equals",
    filter = { segment1, segment2 ->
        segment1 != null && segment2 != null && segment1.equals(segment2)
    }
)

class IntersectsSpatialHandler : AbstractImplicitSpatialHandler(
    relationName = "intersects",
    filter = { segment1, segment2 ->
        segment1 != null && segment2 != null && segment1.orthogonalTo(segment2)
    }
)

class WithinSpatialHandler : AbstractImplicitSpatialHandler(
    relationName = "within",
    filter = { segment1, segment2 ->
        segment1 != null && segment2 != null && segment1.within(segment2)
    }
)

// Uncomment and implement if needed
//class SpatialHandler : AbstractImplicitSpatialHandler(
//    relationName = "",
//    filter = { segment1, segment2 ->
//        segment1 != null && segment2 != null && segment1.(segment2)
//    }
//)