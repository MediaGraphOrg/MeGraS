package org.megras.api.rest.data

import org.megras.data.graph.Quad

data class ApiTriple(val s: String, val p: String, val o: String) {
    constructor(quad: Quad) : this(quad.subject.toString(), quad.predicate.toString(), quad.`object`.toString())
}
