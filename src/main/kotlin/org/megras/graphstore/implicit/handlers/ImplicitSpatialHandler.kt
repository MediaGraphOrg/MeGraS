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
import java.util.concurrent.ConcurrentHashMap


abstract class AbstractImplicitSpatialHandler(
    relationName: String,
    private val filter: (Segmentation?, Segmentation?) -> Boolean
) : ImplicitRelationHandler {

    override val predicate: URIValue = URIValue("${Constants.SPATIAL_SEGMENT_PREFIX}/$relationName")

    protected lateinit var quadSet: ImplicitRelationMutableQuadSet

    override fun init(quadSet: ImplicitRelationMutableQuadSet) {
        this.quadSet = quadSet
    }

    private fun getParent(subject: URIValue): URIValue? {
        return quadSet.filter(
            setOf(subject),
            setOf(MeGraS.SEGMENT_OF.uri),
            null)
            .firstOrNull()?.`object` as? URIValue
    }

    private fun getSpatialCandidatesAndCaches(subject: URIValue): SpatialCandidatesResult? {
        // 1. Get parent of the initial subject
        val parent = getParent(subject) ?: return null

        // 2. Find all siblings sharing the same parent
        val siblings = quadSet.filter(null, setOf(MeGraS.SEGMENT_OF.uri), setOf(parent))
            .mapNotNull { it.subject as? URIValue }
            .toSet()

        val candidates = siblings - subject

        // 3. Bulk fetch segment information for all siblings
        val segmentDefs = quadSet.filter(siblings, setOf(MeGraS.SEGMENT_DEFINITION.uri), null)
            .associateBy({ it.subject as URIValue }, { it.`object` })
        val segmentTypes = quadSet.filter(siblings, setOf(MeGraS.SEGMENT_TYPE.uri), null)
            .associateBy({ it.subject as URIValue }, { it.`object` })

        // 4. Build cache from bulk-fetched data
        val segmentsCache = siblings.associateWith { s ->
            val definition = segmentDefs[s]
            val type = segmentTypes[s]
            if (definition != null && type != null) {
                SegmentationUtil.parseSegmentation(type.toString().removeSuffix("^^String"), definition.toString().removeSuffix("^^String"))
            } else {
                null
            }
        }

        val subjectSegment = segmentsCache[subject]

        return SpatialCandidatesResult(subjectSegment, candidates, segmentsCache)
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
            // Note: The filter arguments are swapped to check the relation from the candidate's perspective
            filter(segmentsCache[it], segment)
        }.toSet()
    }

    override fun findAll(): QuadSet {
        // 1. Bulk fetch all parent, segment definition, and type relations
        val parentQuads = quadSet.filter(null, setOf(MeGraS.SEGMENT_OF.uri), null)
        val segmentDefQuads = quadSet.filter(null, setOf(MeGraS.SEGMENT_DEFINITION.uri), null)
        val segmentTypeQuads = quadSet.filter(null, setOf(MeGraS.SEGMENT_TYPE.uri), null)

        // 2. Create in-memory maps (caches) from the fetched data
        val parentMap = parentQuads.mapNotNull {
            (it.subject as? URIValue)?.let { s -> s to (it.`object` as? URIValue) }
        }.toMap()

        val segmentDefs = segmentDefQuads.mapNotNull {
            (it.subject as? URIValue)?.let { s -> s to it.`object` }
        }.toMap()

        val segmentTypes = segmentTypeQuads.mapNotNull {
            (it.subject as? URIValue)?.let { s -> s to it.`object` }
        }.toMap()

        // 3. Group subjects by their parent, ignoring those without a valid parent
        val subjectsByParent = parentMap.entries.groupBy(
            { it.value }, // group by parent URI
            { it.key } // add subject URI to the group
        ).filterKeys { it != null }

        val resultingQuads = ConcurrentHashMap.newKeySet<Quad>()

        // 4. Process each group of siblings in parallel
        subjectsByParent.values.parallelStream().forEach { siblings ->
            if (siblings.size < 2) return@forEach

            // 5. Build segmentation cache for the current group of siblings
            val segmentsCache = siblings.associateWith { subject ->
                val definition = segmentDefs[subject]
                val type = segmentTypes[subject]
                if (definition != null && type != null) {
                    SegmentationUtil.parseSegmentation(type.toString().removeSuffix("^^String"), definition.toString().removeSuffix("^^String"))
                } else {
                    null
                }
            }

            // 6. Compare pairs within the group (O(N_group^2))
            val siblingList = siblings.toList()
            for (i in siblingList.indices) {
                for (j in (i + 1) until siblingList.size) {
                    val subject1 = siblingList[i]
                    val subject2 = siblingList[j]

                    val segment1 = segmentsCache[subject1]
                    val segment2 = segmentsCache[subject2]

                    if (filter(segment1, segment2)) {
                        resultingQuads.add(Quad(subject1, predicate, subject2))
                    }
                    // Check the inverse relation as well, as the filter might not be symmetric
                    if (filter(segment2, segment1)) {
                        resultingQuads.add(Quad(subject2, predicate, subject1))
                    }
                }
            }
        }

        return BasicQuadSet(resultingQuads)
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

class CoversSpatialHandler : AbstractImplicitSpatialHandler(
    relationName = "covers",
    filter = { segment1, segment2 ->
        segment1 != null && segment2 != null && segment1.covers(segment2)
    }
)

class BesideSpatialHandler : AbstractImplicitSpatialHandler(
    relationName = "beside",
    filter = { segment1, segment2 ->
        segment1 != null && segment2 != null && segment1.beside(segment2)
    }
)

class DisjointSpatialHandler : AbstractImplicitSpatialHandler(
    relationName = "disjoint",
    filter = { segment1, segment2 ->
        segment1 != null && segment2 != null && segment1.disjoint(segment2)
    }
)

class OverlapsSpatialHandler : AbstractImplicitSpatialHandler(
    relationName = "overlaps",
    filter = { segment1, segment2 ->
        segment1 != null && segment2 != null && segment1.overlaps(segment2)
    }
)

/* Uncomment and implement if needed
class SpatialHandler : AbstractImplicitSpatialHandler(
    relationName = "",
    filter = { segment1, segment2 ->
        segment1 != null && segment2 != null && segment1.(segment2)
    }
)
*/