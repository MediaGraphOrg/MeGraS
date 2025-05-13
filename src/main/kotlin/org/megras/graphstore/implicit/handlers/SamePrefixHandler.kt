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

    override fun findObjects(subject: URIValue): Set<URIValue> {
        val subjectPrefix = subject.value.substringBeforeLast("/")
        return quadSet.filter { it.subject is URIValue }
            .map { it.subject as URIValue }
            .filter { it.value.startsWith(subjectPrefix) }
            .toSet()
    }

    override fun findSubjects(`object`: URIValue): Set<URIValue> {
        val objectPrefix = `object`.value.substringBeforeLast("/")
        return quadSet.filter { it.`object` is URIValue }
            .map { it.`object` as URIValue }
            .filter { it.value.startsWith(objectPrefix) }
            .toSet()
    }

    override fun findAll(): QuadSet {
        // find all quads where the subjects and objects have the same prefix
        return BasicQuadSet(
            quadSet.filter { it.subject is URIValue && it.`object` is URIValue }
            .filter {
                (it.subject as URIValue).value.substringBeforeLast("/") == (it.`object` as URIValue).value.substringBeforeLast(
                    "/"
                )
            }
            .toSet())
    }
}
