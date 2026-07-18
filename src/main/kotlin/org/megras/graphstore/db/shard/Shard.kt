package org.megras.graphstore.db.shard

import org.megras.data.graph.VectorValue
import org.megras.graphstore.Distance
import org.megras.graphstore.db.QuadValueId

/**
 * Composite identity for a quad row across a cluster: `(shardOrdinal, localRowId)`.
 * [ClusterQuadSet] is the only producer/consumer; a [Shard] deals in the bare
 * local row id (Long) internally. The ordinal distinguishes rows that collide
 * across disjoint per-shard autoincrement sequences — without it, two shards
 * can both mint row id 42 for unrelated quads, and a merged read or a
 * reverse-resolve would conflate them. Introduced so non-trivial multi-shard
 * policies are safe, not just the single-shard trivial one.
 */
typealias CompositeRowId = Pair<Int, Long>

/**
 * ID-level storage seam: a [Shard] stores quad rows by `(QuadValueId, QuadValueId,
 * QuadValueId)` tuple and owns some set of vector corpora. It sits BELOW the
 * QuadValue-level [org.megras.graphstore.QuadSet] interface; only
 * [ClusterQuadSet] speaks QuadSet and translates QuadValue <-> ID.
 *
 * Identity across shards: methods here use the BARE local row id (Long). It is
 * the caller's responsibility ([ClusterQuadSet]) to pair every such id with the
 * shard ordinal it came from ([CompositeRowId]) whenever ids may be merged or
 * compared across shards. Leaf storage never sees the composite.
 *
 * Responsibilities split from the scalar dictionary:
 * - Scalar value<->ID resolution is central ([org.megras.graphstore.db.dict.QuadValueDictionary]).
 *   A Shard never mints or resolves scalar IDs; it receives QuadValueIds already
 *   resolved by the caller.
 * - Vector value<->ID resolution is coupled to vector content, which this Shard
 *   owns. Vector ID minting and content retrieval are therefore Shard methods,
 *   but only meaningful for corpora this shard owns (see [ownsVectorCorpus]);
 *   calling them for a non-owned corpus is undefined.
 *
 * Co-location invariant: a quad row whose subject or object is a vector ID can
 * only be stored on the shard that owns that vector corpus — the
 * nearest-neighbour join and vector value<->id resolution need local content.
 * A [ShardPolicy] enforces this by routing vector-touching adds to the corpus
 * owner. Vector IDs carry their corpus discriminator in [QuadValueId.first],
 * so a policy can derive the corpus from an ID without asking the shard.
 *
 * Filtering and read methods return quad ROW ids (Long), never [Quad]s.
 * Reverse resolution of a row id to a value-level Quad is the caller's job
 * ([ClusterQuadSet]): scalar IDs via the central dict, vector IDs via the
 * owning shard. Methods are pure leaf storage operations; caching lives above.
 */
interface Shard {

    fun setup()

    /** Get-or-insert: returns the existing row id for (s,p,o) or inserts and returns a new one. */
    fun addQuad(s: QuadValueId, p: QuadValueId, o: QuadValueId): Long

    /** Returns the existing row id for (s,p,o), or null if none. */
    fun quadId(s: QuadValueId, p: QuadValueId, o: QuadValueId): Long?

    /**
     * Batch reverse-resolve row ids to their (s,p,o) id tuples. Missing ids are
     * absent from the map. Fusing row-id -> triple is the caller's
     * ([ClusterQuadSet]) job, not the shard's; ops that produce quad results
     * (filter, textFilterJoin, nearestNeighbor) return row ids and rely on this.
     */
    fun quadTuples(rowIds: Set<Long>): Map<Long, Triple<QuadValueId, QuadValueId, QuadValueId>>

    /** Returns row ids matching the given id-tuple constraints; null operand = wildcard. */
    fun filter(
        s: Collection<QuadValueId>?,
        p: Collection<QuadValueId>?,
        o: Collection<QuadValueId>?
    ): Set<Long>

    fun distinctObjectIds(predicate: QuadValueId): Set<QuadValueId>
    fun distinctSubjectIds(predicate: QuadValueId): Set<QuadValueId>

    /**
     * Joins candidate string-literal row ids (produced by the central dict's
     * full-text search) against this shard's quad rows for [predicate]; returns
     * matching quad row ids. The full-text search itself is central because
     * string literals are central; only the quads-side join is shard-local.
     */
    fun textFilterJoin(predicate: QuadValueId, stringCandidateIds: Set<Long>): Set<Long>

    /**
     * Local nearest-neighbour search on this shard's slice of the corpus.
     * Returns (localRow, distance) pairs, NOT bare row ids: a multi-shard
     * merge needs the distance to compare candidates across shards and keep
     * a global top-k. [Shard.nearestNeighborIds] computes only its own
     * local ranking; [ClusterQuadSet] merges across shards.
     *
     * Distance is the metric value the backing index uses (e.g. pgvector
     * `<=>` cosine, `<#>` dotproduct), as Double. The merge compares these
     * raw values; it does not re-rank by a different metric.
     *
     * The row id is the BARE local id; the caller pairs it with this shard
     * reference (not an ordinal) for any cross-shard bookkeeping — see
     * [ClusterQuadSet], which partitions survivor rows back to their
     * producing shard for reverse-resolution.
     */
    fun nearestNeighborIds(
        predicate: QuadValueId,
        query: VectorValue,
        count: Int,
        distance: Distance,
        invert: Boolean = false
    ): List<Pair<Long, Double>>

    fun insertVectorIds(vectors: Set<VectorValue>): Map<VectorValue, QuadValueId>
    fun lookUpVectorIds(vectors: Set<VectorValue>): Map<VectorValue, QuadValueId>
    fun lookUpVectorValues(ids: Set<QuadValueId>): Map<QuadValueId, VectorValue>

    /**
     * Lookup-then-insert (getOrAdd) for vector content owned by this shard:
     * returns the id of every requested vector, minting only for absent ones.
     * Composes the (cache-inclusive, when the shard impl caches) lookUp +
     * insert leaves so the add path does not throw on already-present vectors.
     */
    fun getOrAddVectorIds(vectors: Set<VectorValue>): Map<VectorValue, QuadValueId> {
        val found = lookUpVectorIds(vectors)
        if (found.size == vectors.size) return found
        return found + insertVectorIds(vectors.filter { it !in found }.toSet())
    }

    fun ownsVectorCorpus(type: VectorValue.Type, length: Int): Boolean
}
