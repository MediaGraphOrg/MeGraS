package org.megras.graphstore.db.shard

import org.megras.data.graph.QuadValue
import org.megras.data.graph.URIValue
import org.megras.graphstore.db.QuadValueId
import java.util.Objects

/**
 * Prefix policy: scalar-quad rows are placed by the subject's URL PREFIX
 * (scheme+host; [URIValue.prefix] -- `LocalQuadValue` exposes the localhost
 * default prefix as its prefix). Non-URI subjects (literals, temporals,
 * vectors-as-subject, anything not an [URIValue]) have no prefix and fall
 * back to a hash of their id pair -- the hash-remainder decision, not a
 * refusal: keeps placement deterministic and spread rather than rejecting the
 * quad or sinking all such rows on one shard. Placement and retrieval coincide
 * (deterministic): [definiteShard] == [addShard].
 *
 * Reads broadcast (see [AbstractBroadcastShardPolicy]): narrow prefix routing
 * is unsafe without replication -- a quad carrying a vector operand is placed
 * on that vector's content shard, not on the subject prefix's shard, so a
 * subject-narrow filter would silently miss vector-operand quads. So even
 * though placement keys on the subject prefix, lookups do NOT narrow to it.
 * Vector content routes by value hash (inherited).
 *
 * Observable distribution SHAPE differs from the uniform policies: a small
 * set of prefixes clusters its rows on a small set of shards (one shard per
 * distinct prefix, modulo hash collision). That clustering is the only
 * distribution feature row-count instrumentation can positively identify
 * (see PlacementShapeTest).
 */
class PrefixShardPolicy(shards: List<Shard>) : AbstractBroadcastShardPolicy(shards) {

    init {
        require(shards.size >= 2) { "PrefixShardPolicy requires >= 2 shards, got ${shards.size}" }
    }

    private fun routeSubject(subjectValue: QuadValue, s: QuadValueId): Shard = when (subjectValue) {
        is URIValue -> route(Objects.hash(subjectValue.prefix()))
        else -> route(Objects.hash(s.first, s.second))
    }

    override fun addShard(subjectValue: QuadValue, s: QuadValueId, p: QuadValueId, o: QuadValueId): Shard =
        routeSubject(subjectValue, s)

    override fun definiteShard(subjectValue: QuadValue, s: QuadValueId, p: QuadValueId, o: QuadValueId): Shard? =
        routeSubject(subjectValue, s)
}
