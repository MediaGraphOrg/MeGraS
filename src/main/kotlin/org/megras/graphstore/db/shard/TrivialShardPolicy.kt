package org.megras.graphstore.db.shard

import org.megras.data.graph.VectorValue
import org.megras.graphstore.db.QuadValueId

/**
 * Degenerate single-shard policy: one shard receives all placements and owns
 * every vector corpus. With one shard the cluster collapses to the single-node
 * path, which is the testable baseline; non-trivial routing is a later swap.
 *
 * Holds a single [Shard] reference; [ClusterQuadSet] separately owns the
 * shard->ordinal mapping used to composite row ids, so this policy never
 * handles ordinals or row-id uniqueness.
 */
class TrivialShardPolicy(private val shard: Shard) : ShardPolicy {

    override fun addShard(s: QuadValueId, p: QuadValueId, o: QuadValueId): Shard = shard

    override fun filterShards(
        s: Collection<QuadValueId>?,
        p: Collection<QuadValueId>?,
        o: Collection<QuadValueId>?
    ): Set<Shard> = setOf(shard)

    override fun nnShards(predicate: QuadValueId, type: VectorValue.Type, length: Int): Set<Shard> = setOf(shard)

    override fun textFilterShards(predicate: QuadValueId): Set<Shard> = setOf(shard)

    override fun distinctShards(predicate: QuadValueId): Set<Shard> = setOf(shard)

    override fun vectorShard(value: VectorValue): Shard? = shard
}
