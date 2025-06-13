package org.megras.graphstore.implicit

import org.megras.data.graph.URIValue
import org.megras.graphstore.QuadSet

interface ImplicitRelationHandler {

    val predicate: URIValue

    fun init(quadSet: ImplicitRelationMutableQuadSet)

    fun findObjects(subject: URIValue): Set<URIValue>

    fun findSubjects(`object`: URIValue): Set<URIValue>

    fun findAll(): QuadSet

}