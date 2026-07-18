package org.megras.graphstore.db.shard

import org.megras.graphstore.db.QuadValueId
import org.megras.data.graph.QuadValue
import java.util.Objects

/**
 * Split policy: the first non-trivial distribution. Scalar-quad rows are
 * PLACED by subject hash; vector content (and vector-operand quads) by vector
 * value hash. Reads broadcast (see [AbstractBroadcastShardPolicy]). Placement
 * and retrieval coincide (deterministic): [definiteShard] == [addShard].
 *
 * [addShard] distributes scalar rows so scalar filter broadcasts are
 * genuinely multi-shard; it is NOT used for read routing (filters broadcast
 * regardless). Narrow scalar filter routing is a deferred replication
 * problem, documented in [AbstractBroadcastShardPolicy].
 *
 * [shards] is the full ordered list; routing is mod its size. The list order
 * is the only shard identity the policy assumes.
 */
class SplitShardPolicy(shards: List<Shard>) : AbstractBroadcastShardPolicy(shards) {

    init {
        require(shards.size >= 2) { "SplitShardPolicy requires >= 2 shards, got ${shards.size}" }
    }

    private fun routeSubject(id: QuadValueId): Shard = route(Objects.hash(id.first, id.second))

    override fun addShard(subjectValue: QuadValue, s: QuadValueId, p: QuadValueId, o: QuadValueId): Shard = routeSubject(s)
    override fun definiteShard(subjectValue: QuadValue, s: QuadValueId, p: QuadValueId, o: QuadValueId): Shard? = routeSubject(s)
}
