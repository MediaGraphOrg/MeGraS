package org.megras.data.graph

import java.io.Serializable

data class Quad(val id: Long? = null, val subject: QuadValue, val predicate: QuadValue, val `object`: QuadValue) : Serializable{
    constructor(subject: QuadValue, predicate: QuadValue, `object`: QuadValue) : this(null, subject, predicate, `object`)

}
