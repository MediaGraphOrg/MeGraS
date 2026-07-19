package org.megras.data.graph

import java.io.Serializable

/**
 * An RDF triple extended to four values. Three are the (subject, predicate,
 * object) terms; the fourth is a stable, storage-independent semantic id computed
 * as a content hash of the three terms — see the [org.megras.id] accessor
 * ([org.megras.id.id]). That id is NOT a field: it is a pure function of the
 * terms, so it is not persisted on the [Quad] instance and carries no storage
 * pointer. The original intent of the fourth value was to make a statement
 * referenceable from another statement's term position (reification /
 * hypergraph); a storage pointer could not safely serve that role.
 *
 * INTERNAL STORAGE POINTERS (per-store autoincrement row ids, composite
 * `(shard, row)` on the cluster substrate) are deliberately NOT carried here.
 * They live internal to backends and are never exposed through [Quad]. Do not
 * reintroduce an id field that holds one.
 *
 * NOTE on serialization: dropping the former `id` field changes the implicit
 * `serialVersionUID` and breaks Java-serialization compatibility with FILE
 * backend stores written by older versions. Pre-release, no upgrade guarantee.
 */
data class Quad(val subject: QuadValue, val predicate: QuadValue, val `object`: QuadValue) : Serializable
