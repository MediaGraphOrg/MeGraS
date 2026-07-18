package org.megras.graphstore.db.shard

import org.megras.graphstore.db.QuadValueId
import org.megras.data.graph.QuadValue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Round-robin policy: scalar-quad rows are PLACED by a stateful counter
 * (`counter++ % n`), independent of content. Reads broadcast (see
 * [AbstractBroadcastShardPolicy]). This is NON-DETERMINISTIC PLACEMENT:
 * [definiteShard] returns null, so [ClusterQuadSet.add] and [.contains]
 * BROADCAST the dup-check across [allShards] -- every add contacts every
 * shard to guard against a duplicate placed on a prior counter shard. That
 * write-fanout is the cost of genuine round-robin distribution; it is why
 * [definiteShard] is null rather than a misnamed single shard.
 *
 * Vector content routes by value hash (inherited); vector-operand quads are
 * unaffected by the counter (see [AbstractBroadcastShardPolicy]).
 *
 * The counter is process-local and monotonic; a replay under different
 * interleaving routes differently, so placement is NOT reproducible across
 * runs. Distribution characteristic, not correctness, is the point.
 */
class RoundRobinShardPolicy(shards: List<Shard>) : AbstractBroadcastShardPolicy(shards) {

    init {
        require(shards.size >= 2) { "RoundRobinShardPolicy requires >= 2 shards, got ${shards.size}" }
    }

    private val counter = AtomicInteger(0)

    override fun addShard(subjectValue: QuadValue, s: QuadValueId, p: QuadValueId, o: QuadValueId): Shard =
        shards[(counter.getAndIncrement() and Int.MAX_VALUE) % shards.size]

    /** Round-robin placement is non-deterministic: no single shard can answer
     *  "does (s,p,o) exist?" -- the caller must broadcast the dup-check. */
    override fun definiteShard(subjectValue: QuadValue, s: QuadValueId, p: QuadValueId, o: QuadValueId): Shard? = null
}
