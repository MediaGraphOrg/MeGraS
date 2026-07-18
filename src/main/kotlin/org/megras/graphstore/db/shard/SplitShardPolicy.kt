package org.megras.graphstore.db.shard

import org.megras.data.graph.DoubleVectorValue
import org.megras.data.graph.FloatVectorValue
import org.megras.data.graph.LongVectorValue
import org.megras.data.graph.VectorValue
import org.megras.graphstore.db.QuadValueId
import java.util.Arrays
import java.util.Objects

/**
 * Split policy: the first non-trivial distribution. Scalar-quad rows are
 * PLACED by subject hash; vector content (and vector-operand quads) by vector
 * value hash. Both spread their data across [shards], so the read paths hit
 * multiple shards and [ClusterQuadSet] merges -- the configuration the merge
 * mechanics (stages A/B) exist to serve.
 *
 * READS BROADCAST. filterShards / textFilterShards / distinctShards /
 * nnShards all return every shard. Narrow routing by subject would be
 * desirable, but it is INCORRECT without replication: a quad carrying a
 * vector operand is placed on that vector's content shard (co-location is
 * required for the NN join), not on its subject's hash shard, so a
 * subject-narrow filter would silently miss vector-operand quads and break
 * parity with the single-node path. Broadcasting finds every placement;
 * value-level dedup (stage A) collapses scalar aliases and equal-content
 * vectors that live on different shards. Narrow filter routing is a deferred
 * replication problem, not addressed here.
 *
 * Placement hashes:
 * - addShard(s, p, o) routes by s. This distributes scalar rows so scalar
 *   filter broadcasts are genuinely multi-shard; it is NOT used for read
 *   routing (filters broadcast regardless).
 * - vectorShard(value) routes by the value's CONTENT, so a vector's id is
 *   minted and resolved only on the shard that holds its content -- the
 *   co-location invariant.
 *
 * Hashing is deterministic and specified (Objects.hash for the id pair,
 * Arrays.hashCode for the content array); it is NOT cryptographic and need
 * not be -- collisions are routing, not correctness, and determinism across
 * restarts is what matters (the persisted rows must route back to the same
 * shard). Distribution quality is a perf concern, deferred.
 *
 * [shards] is the full ordered list; routing is mod its size. The list order
 * is the only shard identity the policy assumes.
 */
class SplitShardPolicy(private val shards: List<Shard>) : ShardPolicy {

    init {
        require(shards.size >= 2) { "SplitShardPolicy requires >= 2 shards, got ${shards.size}" }
    }

    private fun route(hash: Int): Shard = shards[(hash and Int.MAX_VALUE) % shards.size]

    private fun routingHash(id: QuadValueId): Int = Objects.hash(id.first, id.second)

    private fun routingHash(value: VectorValue): Int = when (value) {
        is FloatVectorValue -> Arrays.hashCode(value.vector)
        is DoubleVectorValue -> Arrays.hashCode(value.vector)
        is LongVectorValue -> Arrays.hashCode(value.vector)
        else -> throw IllegalStateException("unknown VectorValue subclass: ${value::class}")
    }

    override fun addShard(s: QuadValueId, p: QuadValueId, o: QuadValueId): Shard = route(routingHash(s))
    override fun allShards(): Collection<Shard> = shards

    override fun filterShards(
        s: Collection<QuadValueId>?,
        p: Collection<QuadValueId>?,
        o: Collection<QuadValueId>?
    ): Set<Shard> = shards.toSet()

    override fun nnShards(predicate: QuadValueId, type: VectorValue.Type, length: Int): Set<Shard> = shards.toSet()

    override fun textFilterShards(predicate: QuadValueId): Set<Shard> = shards.toSet()

    override fun distinctShards(predicate: QuadValueId): Set<Shard> = shards.toSet()

    override fun vectorShard(value: VectorValue): Shard? = route(routingHash(value))
}
