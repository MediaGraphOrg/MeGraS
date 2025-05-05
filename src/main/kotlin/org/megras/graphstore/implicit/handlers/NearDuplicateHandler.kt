package org.megras.graphstore.implicit.handlers

import org.megras.data.graph.URIValue
import org.megras.graphstore.QuadSet
import org.megras.graphstore.implicit.ImplicitRelationHandler
import org.megras.graphstore.implicit.ImplicitRelationMutableQuadSet

class NearDuplicateHandler(override val predicate: URIValue) : ImplicitRelationHandler{

    override fun init(quadSet: ImplicitRelationMutableQuadSet) {

    }

    override fun findObjects(subject: URIValue): Set<URIValue> {
        TODO("Not yet implemented")
    }

    override fun findSubjects(`object`: URIValue): Set<URIValue> {
        TODO("Not yet implemented")
    }

    override fun findAll(): QuadSet {
        TODO("Not yet implemented")
    }
}