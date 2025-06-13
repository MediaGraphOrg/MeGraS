package org.megras.graphstore.implicit.handlers

import org.megras.data.graph.LocalQuadValue
import org.megras.data.graph.Quad
import org.megras.data.graph.URIValue
import org.megras.data.schema.MeGraS
import org.megras.graphstore.BasicQuadSet
import org.megras.graphstore.QuadSet
import org.megras.graphstore.implicit.ImplicitRelationHandler
import org.megras.graphstore.implicit.ImplicitRelationMutableQuadSet
import org.megras.util.Constants

class SegmentSiblingHandler() : ImplicitRelationHandler {

    override val predicate: URIValue = URIValue("${Constants.IMPLICIT_PREFIX}/segmentSibling")

    private lateinit var quadSet: ImplicitRelationMutableQuadSet

    override fun init(quadSet: ImplicitRelationMutableQuadSet) {
        this.quadSet = quadSet
    }

    private fun getSegmentSiblings(objectId: URIValue): Set<URIValue> {
        val parent = this.quadSet.filter(
            setOf(objectId),
            setOf(MeGraS.SEGMENT_OF.uri),
            null
        ).firstOrNull()?.`object` ?: return emptySet()
        return this.quadSet
            .filter(null, setOf(MeGraS.SEGMENT_OF.uri), setOf(parent))
            .map { it.subject }
            .filterIsInstance<URIValue>()
            .filter { it != objectId }
            .toSet()
    }

    override fun findObjects(subject: URIValue): Set<URIValue> {
        return getSegmentSiblings(subject)
    }

    override fun findSubjects(`object`: URIValue): Set<URIValue> {
        return getSegmentSiblings(`object`)
    }

    override fun findAll(): QuadSet {
        val subjects = this.quadSet.filter { it.subject is LocalQuadValue }
            .map { it.subject as LocalQuadValue }
            .toSet()

        val pairs = mutableSetOf<Quad>()
        for (subject in subjects) {
            getSegmentSiblings(subject).forEach { sibling ->
                pairs.add(Quad(subject, predicate, sibling))
            }
        }
        return BasicQuadSet(pairs)
    }
}