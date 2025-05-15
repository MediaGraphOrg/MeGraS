package org.megras.graphstore.implicit.handlers.temporal

import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.data.graph.URIValue
import org.megras.graphstore.BasicMutableQuadSet
import org.megras.graphstore.BasicQuadSet
import org.megras.graphstore.QuadSet
import org.megras.graphstore.implicit.ImplicitRelationHandler
import org.megras.graphstore.implicit.ImplicitRelationMutableQuadSet
import org.megras.lang.sparql.functions.accessors.temporal.EndAccessor
import org.megras.lang.sparql.functions.accessors.temporal.StartAccessor
import org.megras.util.Constants

class AfterHandler : ImplicitRelationHandler {
    override val predicate: URIValue = URIValue("${Constants.TEMPORAL_PREFIX}/after")

    private lateinit var quadSet: ImplicitRelationMutableQuadSet

    override fun init(quadSet: ImplicitRelationMutableQuadSet) {
        this.quadSet = quadSet
    }

    override fun findObjects(subject: URIValue): Set<URIValue> {
        val end = EndAccessor.getEndQV(subject)
        if (end == null) {
            return emptySet()
        }
        // find all subjects that are URI
        // then get the start time of each subject and filter on it
        return this.quadSet.filter { it.subject is URIValue && it.subject != subject }
            .map { it.subject as URIValue }
            .filter { StartAccessor.getStartQV(it) != null && StartAccessor.getStartQV(it)!! >= end }
            .toSet()
    }

    override fun findSubjects(`object`: URIValue): Set<URIValue> {
        val start = StartAccessor.getStartQV(`object`)
        if (start == null) {
            return emptySet()
        }
        // find all subjects that are URI
        // then get the end time of each subject and filter on it
        return this.quadSet.filter { it.subject is URIValue && it.subject != `object` }
            .map { it.subject as URIValue }
            .filter { EndAccessor.getEndQV(it) != null && EndAccessor.getEndQV(it)!! <= start }
            .toSet()
    }

    override fun findAll(): QuadSet {
        val subjects = this.quadSet.filter { it.subject is URIValue}
            .map { it.subject as URIValue }
            .filter { EndAccessor.getEndQV(it) != null && StartAccessor.getStartQV(it) != null }
            .toSet()
        // Create all possible pairs, except self combinations
        // and filter on the end and start times
        val pairs = mutableSetOf<Quad>()
        for (subject in subjects) {
            for (otherSubject in subjects) {
                if (subject != otherSubject) {val start = StartAccessor.getStartQV(otherSubject)
                    val end = EndAccessor.getEndQV(subject)
                    if (start!! >= end!!) {
                        pairs.add(Quad(subject, predicate, otherSubject))
                    }
                }
            }
        }
        return BasicQuadSet(pairs)
    }
}