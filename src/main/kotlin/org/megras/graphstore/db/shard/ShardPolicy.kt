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
 *   a SCALAR-ONLY quad (no vector operand); a vector-operand quad is placed by
 *   [vectorShard] (the corpus owner mints the vector id and stores the row -
 *   co-location invariant, see [Shard]). This split is forced: routing a
 *   vector add to the corpus owner requires knowing the corpus (type info from
 *   the value), but minting the vector id requires the owning shard, so the
 *   add path cannot route from minted ids alone. [ClusterQuadSet] detects
 *   conflicting vector corpora (two distinct corpora among one quad's
 *   operands) and throws - such a quad is unplaceable.
 * - Reads return a SET of shards: future non-trivial policies may spread
 *   scalar-quad storage across shards and broadcast reads; a trivial policy
 *   returns a singleton of the one shard. Vector reads route only to owners.
 * - Routing methods always receive type-bearing [QuadValueId]s or explicit
 *   (type,length) — never bare Long — so a policy can route on type without
 *   interface changes (e.g. a policy that treats vector corpora as a separate
 *   [VectorCorpus]-style concern can be expressed here later).
 * - [vectorShard] maps a corpus to its owner for value<->id and NN routing;
 *   returns null when no shard owns the corpus (read yields empty). It does
 *   NOT participate in reverse-resolution: a vector QuadValueId resolves only
 *   on the shard that produced the enclosing tuple (see [ClusterQuadSet]),
 *   because vector ids are shard-local, not corpus-addressable.
 *
 * Caching is the caller's responsibility, not the policy's.
 *
 * Ordinals: a policy returns [Shard] REFERENCES, never ordinals. [ClusterQuadSet]
 * owns the shard->ordinal mapping (it built the shard set) and composites ids
 * itself; the policy is unconcerned with row-id uniqueness.
 */
interface ShardPolicy {

    /** Places a SCALAR-ONLY quad add on its single owning shard. Vector-operand adds bypass this (see [vectorShard]). */
    fun addShard(s: QuadValueId, p: QuadValueId, o: QuadValueId): Shard

    /**
     * Every shard the policy may route to. Filter operands that are SCALAR ids
     * are broadcast to all of them (scalar ids are global: every shard that
     * holds a row with that id can match, and a row with a given scalar id may
     * live on any shard under a placement policy that routes by subject).
     * Vector operands are NOT broadcast -- they route only to their content
     * shard via [vectorShard] -- so this method is used solely for the scalar
     * broadcast case, never for vectors.
     */
    fun allShards(): Collection<Shard>

    fun filterShards(
        s: Collection<QuadValueId>?,
        p: Collection<QuadValueId>?,
        o: Collection<QuadValueId>?
    ): Set<Shard>

    /**
     * Shards to broadcast an NN search to. A split corpus's content spans
     * multiple shards; every shard holding a slice must contribute its local
     * top-k for [ClusterQuadSet] to merge a global top-k. A trivial policy
     * returns its one shard (nothing to merge).
     */
    fun nnShards(predicate: QuadValueId, type: VectorValue.Type, length: Int): Set<Shard>

    fun textFilterShards(predicate: QuadValueId): Set<Shard>

    fun distinctShards(predicate: QuadValueId): Set<Shard>

    /**
     * Owning shard for a single vector VALUE's value<->id resolution (mint and
     * lookup) and for placing a quad that carries that vector as an operand.
     * Routed by the VALUE, not by (type,length): under a split policy, vectors
     * of the same corpus are spread across shards by content, so the one shard
     * that holds a given vector's content is the only shard that can mint or
     * resolve its id. Null when no shard owns the vector (read yields empty).
     *
     * NN search does NOT use this: a split corpus's content lives on multiple
     * shards, so [nnShards] broadcasts to all of them for a global top-k merge.
     */
    fun vectorShard(value: VectorValue): Shard?
}
