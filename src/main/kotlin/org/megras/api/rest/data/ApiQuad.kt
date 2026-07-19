package org.megras.api.rest.data

import org.megras.data.graph.Quad
import org.megras.id.id

/**
 * REST projection of a [Quad]. `id` is the stable, storage-independent semantic
 * id ([org.megras.id.id] accessor — content hash of (s,p,o)), NOT an internal
 * storage pointer. Safe to persist and compare externally; format is the base64
 * of the multihash bytes and is permanent (changing the canonical encoding
 * changes every id).
 */
data class ApiQuad(val id: String?, val s: String, val p: String, val o: String) {
    constructor(quad: Quad) : this(quad.id.toString(), quad.subject.toString(), quad.predicate.toString(), quad.`object`.toString())
}
