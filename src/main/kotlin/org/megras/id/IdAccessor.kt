package org.megras.id

import org.megras.data.graph.Quad
import java.util.concurrent.ConcurrentHashMap

/**
 * Accessor for the fourth value of a [Quad]: a stable, storage-independent
 * semantic id computed as a content hash of (subject, predicate, object) via
 * [QuadValueCanonical]. Not a field — pure function of the terms, so it is
 * reproducible by anyone and carries no storage pointer. Equal quads (by
 * [org.megras.data.graph.QuadValue] equality) yield equal ids.
 *
 * Results are cached in a [ConcurrentHashMap] so that repeated access on the
 * same [Quad] instance avoids redundant SHA3-256 hashing.
 *
 * SECURITY-SENSITIVE: backed by the permanent canonical encoding in
 * [QuadValueCanonical]; changing that encoding, the digest, or the tag
 * assignments re-hashes every quad. See the quad-semantic-id-redesign plan.
 */
private val idCache = ConcurrentHashMap<Quad, SemanticId>()

val Quad.id: SemanticId
    get() = idCache.computeIfAbsent(this) {
        QuadValueCanonical.semanticId(subject, predicate, `object`)
    }
