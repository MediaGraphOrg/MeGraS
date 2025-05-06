package org.megras.graphstore.implicit.handlers

import org.megras.data.graph.URIValue
import org.megras.graphstore.QuadSet
import org.megras.graphstore.implicit.ImplicitRelationHandler
import org.megras.graphstore.implicit.ImplicitRelationMutableQuadSet
import org.megras.util.Constants

class NearDuplicateHandler() : ImplicitRelationHandler{

    override val predicate: URIValue = URIValue("${Constants.IMPLICIT_PREFIX}/nearDuplicate")

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