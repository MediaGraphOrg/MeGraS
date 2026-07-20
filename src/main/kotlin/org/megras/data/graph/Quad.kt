package org.megras.data.graph

import java.io.Serializable

data class Quad(val id: Long? = null, val subject: QuadValue, val predicate: QuadValue, val `object`: QuadValue) : Serializable{
    constructor(subject: QuadValue, predicate: QuadValue, `object`: QuadValue) : this(null, subject, predicate, `object`)

    // TODO(quad-semantic-id-redesign): `id` is currently an internal storage
    // pointer (per-store autoincrement Long), NOT a stable semantic identifier.
    // The original intent of extending triples to quads was to make this id
    // referenceable from the subject/object position of other triples
    // (subgraph / reification), which a storage pointer cannot safely serve.
    // Planned rework: decouple a SEMANTIC id (content hash of (s,p,o), collision-
    // safe, no cross-shard coordination) from the internal storage pointer.
    // That likely widens this field beyond Long and ripples to serialization.
    // Until then, do not rely on `id` being stable across nodes or restarts,
    // and do not introduce APIs that treat a bare row Long as a public id.
}
