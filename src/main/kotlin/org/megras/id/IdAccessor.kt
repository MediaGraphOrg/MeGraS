package org.megras.id

import org.megras.data.graph.Quad

/**
 * Accessor for the fourth value of a [Quad]: a stable, storage-independent
 * semantic id computed as a content hash of (subject, predicate, object) via
 * [QuadValueCanonical]. Not a field — pure function of the terms, so it is
 * reproducible by anyone and carries no storage pointer. Equal quads (by
 * [org.megras.data.graph.QuadValue] equality) yield equal ids.
 *
 * SECURITY-SENSITIVE: backed by the permanent canonical encoding in
 * [QuadValueCanonical]; changing that encoding, the digest, or the tag
 * assignments re-hashes every quad. See the quad-semantic-id-redesign plan.
 */
val Quad.id: SemanticId
    get() = QuadValueCanonical.semanticId(subject, predicate, `object`)
