package org.megras.api.rest.data

import org.megras.data.graph.Quad

data class ApiQuad(val id: String?, val s: String, val p: String, val o: String) {
    // TODO(quad-semantic-id-redesign): `id` echoes `Quad.id`, currently an
    // internal storage pointer serialized as a string. Once the semantic
    // (content-hash) id lands, this carries that stable reference id instead.
    // Clients that persist or compare `id` externally should expect a format
    // change; treat the current numeric form as ephemeral.
    constructor(quad: Quad) : this(quad.id.toString(), quad.subject.toString(), quad.predicate.toString(), quad.`object`.toString())
}
