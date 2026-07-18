package org.megras.graphstore.db.shard

import org.megras.data.graph.DoubleVectorValue
import org.megras.data.graph.FloatVectorValue
import org.megras.data.graph.LongVectorValue
import org.megras.data.graph.VectorValue
import org.megras.graphstore.db.QuadValueId
import java.util.Arrays

/**
 * Shared scaffold for every non-trivial policy that BROADCASTS reads and
 * routes vector content by VALUE hash. Concrete policies ([SplitShardPolicy],
 * [RoundRobinShardPolicy], [QuadHashShardPolicy], [PrefixShardPolicy]) differ
 * ONLY in scalar [addShard] placement (and the matching [definiteShard]):
 * the read routers, the [allShards] broadcast set, and [vectorShard]'s
 * value-hash routing are invariant across the family and live here.
 *
 * Why this is the shared invariant:
 * - Reads broadcast because narrow scalar routing is unsafe without
 *   replication: a quad carrying a vector operand is placed on that vector's
 *   content shard ([vectorShard]), not on any scalar-id-derived shard, so a
 *   subject-narrow filter would silently miss vector-operand quads. Every
 *   policy in this family declines to narrow; correctness under broadcast is
 *   routing-invariant, so these policies are correctness-equivalent.
 * - Vector content routes by VALUE (not by corpus) so a vector's id is minted
 *   and resolved only on the one shard holding its content -- the co-location
 *   invariant the NN join and the reverse-resolution fuse rely on. [nnShards]
 *   therefore broadcasts: a split corpus spans shards and [ClusterQuadSet]
 *   must merge per-shard top-k into a global top-k.
 *
 * Routing is `mod [shards].size` on the masked hash; [shards] is the full
 * ordered list and its order is the only shard identity assumed. Hashing is
 * deterministic and specified (see [routingHash]); NOT cryptographic -- a
 * collision is a routing, not a correctness, event, and determinism across
 * restarts is what matters (persisted rows must route back to the same shard).
 */
abstract class AbstractBroadcastShardPolicy(protected val shards: List<Shard>) : ShardPolicy {

    protected fun route(hash: Int): Shard = shards[(hash and Int.MAX_VALUE) % shards.size]

    protected fun routingHash(value: VectorValue): Int = when (value) {
        is FloatVectorValue -> Arrays.hashCode(value.vector)
        is DoubleVectorValue -> Arrays.hashCode(value.vector)
        is LongVectorValue -> Arrays.hashCode(value.vector)
        else -> throw IllegalStateException("unknown VectorValue subclass: ${value::class}")
    }

    final override fun allShards(): Collection<Shard> = shards

    final override fun filterShards(
        s: Collection<QuadValueId>?,
        p: Collection<QuadValueId>?,
        o: Collection<QuadValueId>?
    ): Set<Shard> = shards.toSet()

    final override fun nnShards(predicate: QuadValueId, type: VectorValue.Type, length: Int): Set<Shard> = shards.toSet()

    final override fun textFilterShards(predicate: QuadValueId): Set<Shard> = shards.toSet()

    final override fun distinctShards(predicate: QuadValueId): Set<Shard> = shards.toSet()

    final override fun vectorShard(value: VectorValue): Shard? = route(routingHash(value))
}
