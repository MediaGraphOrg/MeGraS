package org.megras.graphstore.derived

import org.megras.data.graph.QuadValue
import org.megras.data.graph.URIValue

interface DerivedRelationHandler<T : QuadValue> {

    val predicate: URIValue

    fun canDerive(subject: URIValue): Boolean

    fun derive(subject: URIValue): T?

}