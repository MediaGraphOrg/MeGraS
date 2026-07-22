package org.megras.graphstore.implicit.handlers

import org.megras.data.graph.Quad
import org.megras.data.graph.TemporalValue
import org.megras.data.graph.URIValue
import org.megras.graphstore.BasicQuadSet
import org.megras.graphstore.QuadSet
import org.megras.graphstore.implicit.ImplicitRelationHandler
import org.megras.graphstore.implicit.ImplicitRelationMutableQuadSet
import org.megras.lang.sparql.functions.accessors.AccessorUtil
import org.megras.util.Constants

abstract class AbstractImplicitTemporalHandler(
    override val predicate: URIValue,
    private val getStart: (URIValue, QuadSet) -> TemporalValue?,
    private val getEnd: (URIValue, QuadSet) -> TemporalValue?,
    private val compare: (start1: TemporalValue?, end1: TemporalValue?, start2: TemporalValue?, end2: TemporalValue?) -> Boolean
) : ImplicitRelationHandler {

    protected lateinit var quadSet: ImplicitRelationMutableQuadSet

    private var cachedSubjects: Set<URIValue>? = null
    private val cachedStartCache: MutableMap<URIValue, TemporalValue?> = mutableMapOf()
    private val cachedEndCache: MutableMap<URIValue, TemporalValue?> = mutableMapOf()

    override fun init(quadSet: ImplicitRelationMutableQuadSet) {
        this.quadSet = quadSet
    }

    private fun getAllSubjects(): Set<URIValue> {
        cachedSubjects?.let { return it }
        val subjects = mutableSetOf<URIValue>()
        for (quad in quadSet) {
            if (quad.subject is URIValue) {
                subjects.add(quad.subject)
            }
        }
        cachedSubjects = subjects
        return subjects
    }

    private fun getStartTime(subject: URIValue): TemporalValue? {
        return cachedStartCache.getOrPut(subject) { getStart(subject, quadSet) }
    }

    private fun getEndTime(subject: URIValue): TemporalValue? {
        return cachedEndCache.getOrPut(subject) { getEnd(subject, quadSet) }
    }

    override fun findObjects(subject: URIValue): Set<URIValue> {
        val start = getStartTime(subject)
        val end = getEndTime(subject)
        return getAllSubjects().filter { it != subject && compare(start, end, getStartTime(it), getEndTime(it)) }.toSet()
    }

    override fun findSubjects(`object`: URIValue): Set<URIValue> {
        val start = getStartTime(`object`)
        val end = getEndTime(`object`)
        return getAllSubjects().filter { it != `object` && compare(getStartTime(it), getEndTime(it), start, end) }.toSet()
    }

    override fun findAll(): QuadSet {
        val subjects = getAllSubjects().toList()
        // Pre-warm caches
        for (s in subjects) {
            getStartTime(s)
            getEndTime(s)
        }
        val pairs = mutableSetOf<Quad>()
        for (subject1 in subjects) {
            val start1 = cachedStartCache[subject1]
            val end1 = cachedEndCache[subject1]
            for (subject2 in subjects) {
                if (subject1 != subject2) {
                    if (compare(start1, end1, cachedStartCache[subject2], cachedEndCache[subject2])) {
                        pairs.add(Quad(subject1, predicate, subject2))
                    }
                }
            }
        }
        return BasicQuadSet(pairs)
    }
}

// Subclasses for Object and Segment handlers
abstract class ImplicitTemporalObjectHandler(
    predicate: URIValue,
    compare: (start1: TemporalValue?, end1: TemporalValue?, start2: TemporalValue?, end2: TemporalValue?) -> Boolean
) : AbstractImplicitTemporalHandler(
    predicate,
    getStart = { subject, quads -> AccessorUtil.getStart(subject, quads) },
    getEnd = { subject, quads -> AccessorUtil.getEnd(subject, quads) },
    compare
)

abstract class ImplicitTemporalSegmentHandler(
    predicate: URIValue,
    compare: (start1: TemporalValue?, end1: TemporalValue?, start2: TemporalValue?, end2: TemporalValue?) -> Boolean
) : AbstractImplicitTemporalHandler(
    predicate,
    getStart = { subject, quads -> AccessorUtil.getStart(subject, quads) },
    getEnd = { subject, quads -> AccessorUtil.getEnd(subject, quads) },
    compare
)

// Common compare functions
private val afterCompare = { start1: TemporalValue?, _: TemporalValue?, _: TemporalValue?, end2: TemporalValue? ->
    start1 != null && end2 != null && start1 >= end2
}

private val precedesCompare = { _: TemporalValue?, end1: TemporalValue?, start2: TemporalValue?, _: TemporalValue? ->
    end1 != null && start2 != null && end1 < start2
}

private val finishesCompare = { start1: TemporalValue?, end1: TemporalValue?, start2: TemporalValue?, end2: TemporalValue? ->
    end1 != null && end2 != null && end1 == end2 && start1 != null && start2 != null && start1 > start2
}

private val meetsCompare = { start1: TemporalValue?, end1: TemporalValue?, start2: TemporalValue?, end2: TemporalValue? ->
    (start1 != null && end2 != null && start1 == end2) || (end1 != null && start2 != null && end1 == start2)
}

private val startsCompare = { start1: TemporalValue?, end1: TemporalValue?, start2: TemporalValue?, end2: TemporalValue? ->
    start1 != null && start2 != null && start1 == start2 && end1 != null && end2 != null && end1 < end2
}

private val containsCompare = { start1: TemporalValue?, end1: TemporalValue?, start2: TemporalValue?, end2: TemporalValue? ->
    start1 != null && end1 != null && start2 != null && end2 != null &&
            start1 < start2 && end1 > end2
}

private val equalsCompare = { start1: TemporalValue?, end1: TemporalValue?, start2: TemporalValue?, end2: TemporalValue? ->
    start1 != null && end1 != null && start2 != null && end2 != null &&
            start1 == start2 && end1 == end2
}

private val overlapsCompare = { start1: TemporalValue?, end1: TemporalValue?, start2: TemporalValue?, end2: TemporalValue? ->
    start1 != null && end1 != null && start2 != null && end2 != null &&
            ((start1 < start2 && start2 < end1 && end1 < end2) || (start2 < start1 && start1 < end2 && end2 < end1))
}

// Object Handlers
class AfterObjectHandler : ImplicitTemporalObjectHandler(
    predicate = URIValue("${Constants.TEMPORAL_OBJECT_PREFIX}/after"),
    compare = afterCompare
)

class PrecedesObjectHandler : ImplicitTemporalObjectHandler(
    predicate = URIValue("${Constants.TEMPORAL_OBJECT_PREFIX}/precedes"),
    compare = precedesCompare
)

class FinishesObjectHandler : ImplicitTemporalObjectHandler(
    predicate = URIValue("${Constants.TEMPORAL_OBJECT_PREFIX}/finishes"),
    compare = finishesCompare
)

class MeetsObjectHandler : ImplicitTemporalObjectHandler(
    predicate = URIValue("${Constants.TEMPORAL_OBJECT_PREFIX}/meets"),
    compare = meetsCompare
)

class StartsObjectHandler : ImplicitTemporalObjectHandler(
    predicate = URIValue("${Constants.TEMPORAL_OBJECT_PREFIX}/starts"),
    compare = startsCompare
)

class ContainsObjectHandler : ImplicitTemporalObjectHandler(
    predicate = URIValue("${Constants.TEMPORAL_OBJECT_PREFIX}/contains"),
    compare = containsCompare
)

class EqualsObjectHandler : ImplicitTemporalObjectHandler(
    predicate = URIValue("${Constants.TEMPORAL_OBJECT_PREFIX}/equals"),
    compare = equalsCompare
)

class OverlapsObjectHandler : ImplicitTemporalObjectHandler(
    predicate = URIValue("${Constants.TEMPORAL_OBJECT_PREFIX}/overlaps"),
    compare = overlapsCompare
)

// Segment Handlers
class AfterSegmentHandler : ImplicitTemporalSegmentHandler(
    predicate = URIValue("${Constants.TEMPORAL_SEGMENT_PREFIX}/after"),
    compare = afterCompare
)

class PrecedesSegmentHandler : ImplicitTemporalSegmentHandler(
    predicate = URIValue("${Constants.TEMPORAL_SEGMENT_PREFIX}/precedes"),
    compare = precedesCompare
)

class FinishesSegmentHandler : ImplicitTemporalSegmentHandler(
    predicate = URIValue("${Constants.TEMPORAL_SEGMENT_PREFIX}/finishes"),
    compare = finishesCompare
)

class MeetsSegmentHandler : ImplicitTemporalSegmentHandler(
    predicate = URIValue("${Constants.TEMPORAL_SEGMENT_PREFIX}/meets"),
    compare = meetsCompare
)

class StartsSegmentHandler : ImplicitTemporalSegmentHandler(
    predicate = URIValue("${Constants.TEMPORAL_SEGMENT_PREFIX}/starts"),
    compare = startsCompare
)

class ContainsSegmentHandler : ImplicitTemporalSegmentHandler(
    predicate = URIValue("${Constants.TEMPORAL_SEGMENT_PREFIX}/contains"),
    compare = containsCompare
)

class EqualsSegmentHandler : ImplicitTemporalSegmentHandler(
    predicate = URIValue("${Constants.TEMPORAL_SEGMENT_PREFIX}/equals"),
    compare = equalsCompare
)

class OverlapsSegmentHandler : ImplicitTemporalSegmentHandler(
    predicate = URIValue("${Constants.TEMPORAL_SEGMENT_PREFIX}/overlaps"),
    compare = overlapsCompare
)
