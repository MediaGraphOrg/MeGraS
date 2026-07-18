package org.megras.graphstore.db.shard

import org.megras.graphstore.db.QuadValueId
import org.megras.data.graph.QuadValue
import java.util.Objects

/**
 * Quad-hash policy: scalar-quad rows are placed by a hash of the WHOLE quad
 * `(s,p,o)` -- not of the subject alone. Distributes more uniformly than a
 * subject hash when one subject dominates the corpus, at the cost of
 * defeating any future subject-narrow read (a non-goal under broadcast reads;
 * see [AbstractBroadcastShardPolicy]). Placement and retrieval coincide
 * (deterministic): [definiteShard] == [addShard].
 *
 * Vector content routes by value hash (inherited); vector-operand quads bypass
 * this ([AbstractBroadcastShardPolicy]).
 *
 * Observable distribution shape (uniform across shards) is INDISTINGUISHABLE
 * from [SplitShardPolicy] by row-count alone -- telling them apart needs
 * per-subject or per-quad placement instrumentation, not just counts.
 */
class QuadHashShardPolicy(shards: List<Shard>) : AbstractBroadcastShardPolicy(shards) {

    init {
        require(shards.size >= 2) { "QuadHashShardPolicy requires >= 2 shards, got ${shards.size}" }
    }

    private fun routeQuad(s: QuadValueId, p: QuadValueId, o: QuadValueId): Shard =
        route(Objects.hash(s.first, s.second, p.first, p.second, o.first, o.second))

    override fun addShard(subjectValue: QuadValue, s: QuadValueId, p: QuadValueId, o: QuadValueId): Shard = routeQuad(s, p, o)
    override fun definiteShard(subjectValue: QuadValue, s: QuadValueId, p: QuadValueId, o: QuadValueId): Shard? = routeQuad(s, p, o)
}
