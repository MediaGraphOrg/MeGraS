package org.megras.graphstore.implicit.handlers.temporal

import org.megras.data.graph.URIValue
import org.megras.graphstore.QuadSet
import org.megras.graphstore.implicit.ImplicitRelationHandler
import org.megras.graphstore.implicit.ImplicitRelationMutableQuadSet
import org.megras.lang.sparql.functions.accessors.temporal.StartAccessor
import org.megras.util.Constants

class AfterHandler : ImplicitRelationHandler {
    override val predicate: URIValue = URIValue("${Constants.TEMPORAL_PREFIX}/after")

    private lateinit var quadSet: ImplicitRelationMutableQuadSet

    override fun init(quadSet: ImplicitRelationMutableQuadSet) {
        this.quadSet = quadSet
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