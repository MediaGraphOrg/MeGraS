package org.megras.graphstore.implicit.handlers.temporal

import org.megras.data.graph.Quad
import org.megras.data.graph.TemporalValue
import org.megras.data.graph.URIValue
import org.megras.graphstore.BasicQuadSet
import org.megras.graphstore.QuadSet
import org.megras.graphstore.implicit.ImplicitRelationHandler
import org.megras.graphstore.implicit.ImplicitRelationMutableQuadSet
import org.megras.lang.sparql.functions.accessors.temporal.AccessorUtil

abstract class ImplicitTemporalHandler(
    override val predicate: URIValue,
    private val compare: (start1: TemporalValue?, end1: TemporalValue?, start2: TemporalValue?, end2: TemporalValue?) -> Boolean
) : ImplicitRelationHandler {

    protected lateinit var quadSet: ImplicitRelationMutableQuadSet

    override fun init(quadSet: ImplicitRelationMutableQuadSet) {
        this.quadSet = quadSet
    }

    private fun getTemporalCandidatesAndCaches(subject: URIValue): TemporalCandidatesResult {
        val start = AccessorUtil.getStart(subject)
        val end = AccessorUtil.getEnd(subject)
        val candidates = quadSet.filter { it.subject is URIValue && it.subject != subject }
            .map { it.subject as URIValue }
            .toSet()
        val startCache = candidates.associateWith { AccessorUtil.getStart(it) }
        val endCache = candidates.associateWith { AccessorUtil.getEnd(it) }
        return TemporalCandidatesResult(start, end, candidates, startCache, endCache)
    }

    private data class TemporalCandidatesResult(
        val start: TemporalValue?,
        val end: TemporalValue?,
        val candidates: Set<URIValue>,
        val startCache: Map<URIValue, TemporalValue?>,
        val endCache: Map<URIValue, TemporalValue?>
    )

    override fun findObjects(subject: URIValue): Set<URIValue> {
        val (start, end, candidates, startCache, endCache) = getTemporalCandidatesAndCaches(subject)
        return candidates.filter {
            compare(start, end, startCache[it], endCache[it])
        }.toSet()
    }

    override fun findSubjects(`object`: URIValue): Set<URIValue> {
        val (start, end, candidates, startCache, endCache) = getTemporalCandidatesAndCaches(`object`)
        return candidates.filter {
            compare(startCache[it], endCache[it], start, end)
        }.toSet()
    }

    override fun findAll(): QuadSet {
        val subjects = quadSet.filter { it.subject is URIValue }
            .map { it.subject as URIValue }
            .toSet()
        val startCache = subjects.associateWith { AccessorUtil.getStart(it) }
        val endCache = subjects.associateWith { AccessorUtil.getEnd(it) }
        val pairs = mutableSetOf<Quad>()
        for (subject1 in subjects) {
            val start1 = startCache[subject1]
            val end1 = endCache[subject1]
            for (subject2 in subjects) {
                if (subject1 != subject2) {
                    val start2 = startCache[subject2]
                    val end2 = endCache[subject2]
                    if (compare(start1, end1, start2, end2)) {
                        pairs.add(Quad(subject1, predicate, subject2))
                    }
                }
            }
        }
        return BasicQuadSet(pairs)
    }
}