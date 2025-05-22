package org.megras.graphstore.implicit.handlers

import org.megras.data.graph.Quad
import org.megras.data.graph.URIValue
import org.megras.data.schema.MeGraS
import org.megras.graphstore.BasicQuadSet
import org.megras.graphstore.QuadSet
import org.megras.graphstore.implicit.ImplicitRelationHandler
import org.megras.graphstore.implicit.ImplicitRelationMutableQuadSet
import org.megras.segmentation.Bounds
import org.megras.util.Constants


abstract class AbstractImplicitSpatialHandler(
    override val predicate: URIValue,
    private val filter: (Bounds?, Bounds?) -> Boolean
) : ImplicitRelationHandler {

    protected lateinit var quadSet: ImplicitRelationMutableQuadSet

    override fun init(quadSet: ImplicitRelationMutableQuadSet) {
        this.quadSet = quadSet
    }

    private fun getBounds(subject: URIValue): Bounds? {
        val boundsQuad = quadSet.filter(
            setOf(subject),
            setOf(MeGraS.SEGMENT_BOUNDS.uri),
            null)
            .firstOrNull() ?: return null
        return Bounds(boundsQuad.`object`.toString().removeSuffix("^^String"))
    }

    private fun getParent(subject: URIValue): URIValue? {
        return quadSet.filter(
            setOf(subject),
            setOf(MeGraS.SEGMENT_OF.uri),
            null)
            .firstOrNull()?.`object` as? URIValue
    }

    private fun getSpatialCandidatesAndCaches(subject: URIValue): SpatialCandidatesResult? {
        val bounds = getBounds(subject) ?: return null
        val parent = getParent(subject) ?: return null
        //TODO: get actual top ancestor
        //TODO: check behavior of segment of segment
        val candidates = quadSet.filter { it.subject is URIValue && it.subject != subject && parent == getParent(it.subject)}
            .map { it.subject as URIValue }
            .toSet()
        val boundsCache = candidates.associateWith { getBounds(it) }
        return SpatialCandidatesResult(bounds, candidates, boundsCache)
    }

    private data class SpatialCandidatesResult(
        val bounds: Bounds?,
        val candidates: Set<URIValue>,
        val boundsCache: Map<URIValue, Bounds?>
    )

    override fun findObjects(subject: URIValue): Set<URIValue> {
        val (bounds, candidates, boundsCache) = getSpatialCandidatesAndCaches(subject) ?: return emptySet()
        return candidates.filter {
            filter(bounds, boundsCache[it])
        }.toSet()
    }

    override fun findSubjects(`object`: URIValue): Set<URIValue> {
        val (bounds, candidates, boundsCache) = getSpatialCandidatesAndCaches(`object`) ?: return emptySet()
        return candidates.filter {
            filter(boundsCache[it], bounds)
        }.toSet()
    }

    override fun findAll(): QuadSet {
        val subjects = quadSet.filter { it.subject is URIValue }
            .map { it.subject as URIValue }
            .toSet()
        val boundsCache = subjects.associateWith { getBounds(it) }
        val pairs = mutableSetOf<Quad>()
        for (subject1 in subjects) {
            val bounds1 = boundsCache[subject1]
            for (subject2 in subjects) {
                if (subject1 != subject2) {
                    val bounds2 = boundsCache[subject2]
                    if (filter(bounds1, bounds2)) {
                        pairs.add(Quad(subject1, predicate, subject2))
                    }
                }
            }
        }
        return BasicQuadSet(pairs)
    }
}

class ContainsSpatialHandler : AbstractImplicitSpatialHandler(
    predicate = URIValue("${Constants.SPATIAL_SEGMENT_PREFIX}/contains"),
    filter = { bounds1, bounds2 ->
        bounds1 != null && bounds2 != null && bounds1.contains(bounds2)
    }
)
