package org.megras.graphstore.db.shard

import org.megras.data.graph.VectorValue
import org.megras.graphstore.db.QuadValueId

/**
 * Routing seam between [ClusterQuadSet] and a set of [Shard]s. A policy maps
 * each operation to the shard(s) that may hold the relevant rows. It is the
 * swappable knob for distribution-strategy experiments: different policies
 * place and route quad rows differently.
 *
 * Shape rules:
 * - Writes return a SINGLE owner: placement is deterministic. [addShard] places
 *   a quad (scalar or vector); the policy inspects the QuadValueId type
 *   discriminators to decide, and for a vector operand MUST return the corpus
 *   owner (co-location invariant — see [Shard]). Conflicting vector corpora in
 *   one quad are unplaceable; the policy signals that by throwing.
 * - Reads return a SET of shards: future non-trivial policies may spread
 *   scalar-quad storage across shards and broadcast reads; a trivial policy
 *   returns a singleton of the one shard. Vector reads route only to owners.
 * - Routing methods always receive type-bearing [QuadValueId]s or explicit
 *   (type,length) — never bare Long — so a policy can route on type without
 *   interface changes (e.g. a policy that treats vector corpora as a separate
 *   [VectorCorpus]-style concern can be expressed here later).
 * - [vectorShard] maps a corpus to its owner for value<->id / reverse-resolve
 *   routing; returns null when no shard owns the corpus (read yields empty).
 *
 * Caching is the caller's responsibility, not the policy's.
 *
 * Ordinals: a policy returns [Shard] REFERENCES, never ordinals. [ClusterQuadSet]
 * owns the shard->ordinal mapping (it built the shard set) and composites ids
 * itself; the policy is unconcerned with row-id uniqueness.
 */
interface ShardPolicy {

    /** Places a quad add on its single owning shard. */
    fun addShard(s: QuadValueId, p: QuadValueId, o: QuadValueId): Shard

    fun filterShards(
        s: Collection<QuadValueId>?,
        p: Collection<QuadValueId>?,
        o: Collection<QuadValueId>?
    ): Set<Shard>

    fun nnShards(predicate: QuadValueId, type: VectorValue.Type, length: Int): Set<Shard>

    fun textFilterShards(predicate: QuadValueId): Set<Shard>

    fun distinctShards(predicate: QuadValueId): Set<Shard>

    /** Owner for a vector corpus's value<->id and NN routing; null if none. */
    fun vectorShard(type: VectorValue.Type, length: Int): Shard?

    /** Owner for reverse-resolving a vector QuadValueId to its value; null if none. */
    fun vectorShard(id: QuadValueId): Shard?
}
