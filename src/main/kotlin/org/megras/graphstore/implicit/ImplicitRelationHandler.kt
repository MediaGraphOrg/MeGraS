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

interface RegexImplicitRelationHandler {
    /**
     * Returns true if this handler can handle the given predicate URI, and extracts any parameters (e.g., k)
     */
    fun matchPredicate(predicate: URIValue): Map<String, String>?

    /**
     * Returns a handler instance for the given parameters (e.g., k)
     */
    fun getHandler(params: Map<String, String>): ImplicitRelationHandler
}
