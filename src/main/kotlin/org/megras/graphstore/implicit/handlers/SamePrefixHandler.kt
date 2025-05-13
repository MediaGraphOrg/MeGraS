package org.megras.graphstore.implicit.handlers

import org.megras.data.graph.URIValue
import org.megras.graphstore.BasicQuadSet
import org.megras.graphstore.QuadSet
import org.megras.graphstore.implicit.ImplicitRelationHandler
import org.megras.graphstore.implicit.ImplicitRelationMutableQuadSet
import org.megras.util.Constants

class SamePrefixHandler() : ImplicitRelationHandler{

    override val predicate: URIValue = URIValue("${Constants.IMPLICIT_PREFIX}/samePrefix")

    private lateinit var quadSet: ImplicitRelationMutableQuadSet

    override fun init(quadSet: ImplicitRelationMutableQuadSet) {
        this.quadSet = quadSet
    }

    // same function because relationship is symmetric
    private fun findValues(value: URIValue): Set<URIValue> {
        val prefix = value.value.substringBeforeLast("/")
        return this.quadSet.filter { it.subject is URIValue }
            .map { it.subject as URIValue }
            .filter { it.value.startsWith(prefix) }
            .toSet()
    }

    override fun findObjects(subject: URIValue): Set<URIValue> {
        return findValues(subject)
    }

    override fun findSubjects(`object`: URIValue): Set<URIValue> {
        return findValues(`object`)
    }

    override fun findAll(): QuadSet {
        // find all quads where the subjects and objects have the same prefix
        return BasicQuadSet(
            this.quadSet.filter { it.subject is URIValue && it.`object` is URIValue }
            .filter {
                (it.subject as URIValue).value.substringBeforeLast("/") == (it.`object` as URIValue).value.substringBeforeLast("/")
            }
            .toSet())
    }
}
