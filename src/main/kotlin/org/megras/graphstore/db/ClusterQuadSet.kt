package org.megras.graphstore.db

import org.megras.data.graph.DoubleValue
import org.megras.data.graph.LongValue
import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.data.graph.StringValue
import org.megras.data.graph.URIValue
import org.megras.data.graph.VectorValue
import org.megras.data.graph.LocalQuadValue
import org.megras.graphstore.BasicQuadSet
import org.megras.graphstore.Distance
import org.megras.id.SemanticId
import org.megras.id.id
import org.megras.graphstore.MutableQuadSet
import org.megras.graphstore.QuadSet
import org.megras.graphstore.db.dict.QuadValueDictionary
import org.megras.graphstore.db.shard.Shard
import org.megras.graphstore.db.shard.ShardPolicy
import org.megras.data.graph.TemporalValue

/**
 * QuadSet adapter over a central [QuadValueDictionary] and a set of [Shard]s
 * routed by a [ShardPolicy]: the only QuadSet speaker in the distributed
 * backend. Translates QuadValue <-> ID (scalar via the dict, vector via the
 * owning shard), routes operations per policy, merges results, and
 * reverse-resolves IDs back to values.
 *
 * No caches (TODO: port the AbstractDbStore caches to keep perf comparable to
 * the single-node PostgresStore path; their absence skews benchmarks but not
 * correctness). Read paths fuse to value-level Quads without exposing row ids,
 * so [shard.CompositeRowId] is not constructed here and the shard->ordinal
 * mapping is not needed - it matters only if a future API exposes row ids.
 * [getId] is unsupported pending the quad semantic-id redesign (a bare row
 * Long is meaningless across shards).
 *
 * Full-scan ops (toSet, plus, size, isEmpty, iterator) are left as TODO,
 * matching HybridMutableQuadSet: callers in the query path rarely need them,
 * and a full scan over a sharded graph is expensive. contains/containsAll/add
 * ARE supported: placement is deterministic, so they route to the owning shard.
 */
class ClusterQuadSet(
    private val dictionary: QuadValueDictionary,
    private val policy: ShardPolicy
) : MutableQuadSet {

    // ---- QuadValue <-> ID (no caches) --------------------------------------

    private fun owningShardFor(value: VectorValue) = policy.vectorShard(value)

    private fun resolveValueLookup(value: QuadValue): QuadValueId? = when (value) {
        is DoubleValue -> dictionary.lookUpDoubleValueIds(setOf(value))[value]
        is StringValue -> dictionary.lookUpStringValueIds(setOf(value))[value]
        is LongValue -> QuadValueId(AbstractDbStore.LONG_LITERAL_TYPE, value.value)
        is LocalQuadValue -> {
            val suffix = dictionary.lookUpSuffixIds(setOf(value.suffix()))[value.suffix()] ?: return null
            QuadValueId(AbstractDbStore.LOCAL_URI_TYPE, suffix)
        }
        is URIValue -> {
            val prefix = dictionary.lookUpPrefixIds(setOf(value.prefix()))[value.prefix()] ?: return null
            val suffix = dictionary.lookUpSuffixIds(setOf(value.suffix()))[value.suffix()] ?: return null
            QuadValueId(prefix, suffix)
        }
        is VectorValue -> owningShardFor(value)?.lookUpVectorIds(setOf(value))?.get(value)
        is TemporalValue -> TODO("TemporalValue not yet supported")
    }

    private fun resolveValueAdd(value: QuadValue): QuadValueId? = when (value) {
        is DoubleValue -> dictionary.getOrAddDoubleValueIds(setOf(value))[value]
        is StringValue -> dictionary.getOrAddStringValueIds(setOf(value))[value]
        is LongValue -> QuadValueId(AbstractDbStore.LONG_LITERAL_TYPE, value.value)
        is LocalQuadValue -> {
            val suffix = dictionary.getOrAddSuffixValues(setOf(value.suffix()))[value.suffix()]!!
            QuadValueId(AbstractDbStore.LOCAL_URI_TYPE, suffix)
        }
        is URIValue -> {
            val prefix = dictionary.getOrAddPrefixValues(setOf(value.prefix()))[value.prefix()]!!
            val suffix = dictionary.getOrAddSuffixValues(setOf(value.suffix()))[value.suffix()]!!
            QuadValueId(prefix, suffix)
        }
        is VectorValue -> owningShardFor(value)?.getOrAddVectorIds(setOf(value))?.get(value)
        is TemporalValue -> TODO("TemporalValue not yet supported")
    }

    private fun resolveValuesLookup(values: Collection<QuadValue>): Map<QuadValue, QuadValueId> {
        if (values.isEmpty()) return emptyMap()
        val result = mutableMapOf<QuadValue, QuadValueId>()
        val doubles = mutableSetOf<DoubleValue>()
        val strings = mutableSetOf<StringValue>()
        val uris = mutableSetOf<URIValue>()
        val vectors = mutableSetOf<VectorValue>()
        for (v in values) {
            when (v) {
                is DoubleValue -> doubles.add(v)
                is StringValue -> strings.add(v)
                is LongValue -> result[v] = QuadValueId(AbstractDbStore.LONG_LITERAL_TYPE, v.value)
                is URIValue -> uris.add(v)
                is VectorValue -> vectors.add(v)
                is TemporalValue -> TODO("TemporalValue not yet supported")
            }
        }
        if (doubles.isNotEmpty()) result.putAll(dictionary.lookUpDoubleValueIds(doubles))
        if (strings.isNotEmpty()) result.putAll(dictionary.lookUpStringValueIds(strings))
        if (uris.isNotEmpty()) {
            val prefixes = mutableMapOf<String, Int>()
            val suffixes = mutableMapOf<String, Long>()
            val pVals = uris.filter { it !is LocalQuadValue }.map { it.prefix() }.toSet()
            val sVals = uris.map { it.suffix() }.toSet()
            if (pVals.isNotEmpty()) prefixes.putAll(dictionary.lookUpPrefixIds(pVals))
            if (sVals.isNotEmpty()) suffixes.putAll(dictionary.lookUpSuffixIds(sVals))
            for (u in uris) {
                val p = if (u is LocalQuadValue) AbstractDbStore.LOCAL_URI_TYPE else prefixes[u.prefix()]
                val s = suffixes[u.suffix()]
                if (p != null && s != null) result[u] = QuadValueId(p, s)
            }
        }
        if (vectors.isNotEmpty()) {
            vectors.groupBy { policy.vectorShard(it) }.forEach { (shard, group) ->
                if (shard != null) result.putAll(shard.lookUpVectorIds(group.toSet()))
            }
        }
        return result
    }

    private fun resolveValuesAdd(values: Collection<QuadValue>): Map<QuadValue, QuadValueId> {
        if (values.isEmpty()) return emptyMap()
        val result = mutableMapOf<QuadValue, QuadValueId>()
        val doubles = mutableSetOf<DoubleValue>()
        val strings = mutableSetOf<StringValue>()
        val uris = mutableSetOf<URIValue>()
        val vectors = mutableSetOf<VectorValue>()
        for (v in values) {
            when (v) {
                is DoubleValue -> doubles.add(v)
                is StringValue -> strings.add(v)
                is LongValue -> result[v] = QuadValueId(AbstractDbStore.LONG_LITERAL_TYPE, v.value)
                is URIValue -> uris.add(v)
                is VectorValue -> vectors.add(v)
                is TemporalValue -> TODO("TemporalValue not yet supported")
            }
        }
        if (doubles.isNotEmpty()) result.putAll(dictionary.getOrAddDoubleValueIds(doubles))
        if (strings.isNotEmpty()) result.putAll(dictionary.getOrAddStringValueIds(strings))
        if (uris.isNotEmpty()) {
            val prefixes = mutableMapOf<String, Int>()
            val suffixes = mutableMapOf<String, Long>()
            val pVals = uris.filter { it !is LocalQuadValue }.map { it.prefix() }.toSet()
            val sVals = uris.map { it.suffix() }.toSet()
            if (pVals.isNotEmpty()) prefixes.putAll(dictionary.getOrAddPrefixValues(pVals))
            if (sVals.isNotEmpty()) suffixes.putAll(dictionary.getOrAddSuffixValues(sVals))
            for (u in uris) {
                val p = if (u is LocalQuadValue) AbstractDbStore.LOCAL_URI_TYPE else prefixes[u.prefix()]
                val s = suffixes[u.suffix()]
                if (p != null && s != null) result[u] = QuadValueId(p, s)
            }
        }
        if (vectors.isNotEmpty()) {
            vectors.groupBy { policy.vectorShard(it) }.forEach { (shard, group) ->
                if (shard != null) result.putAll(shard.getOrAddVectorIds(group.toSet()))
            }
        }
        return result
    }

    /**
     * Reverse-resolve SCALAR ids only (double/long/string/uri-prefix-suffix).
     * Scalar ids are GLOBAL: the central dictionary mints them, so their
     * meaning is independent of which shard referenced them, and one batched
     * dict call resolves them regardless of provenance.
     *
     * Vector ids are intentionally NOT handled here. A vector QuadValueId is
     * SHARD-LOCAL: each shard mints its own vector_values_<id> tables and the
     * id discriminator is per-shard, so an id from shard N is uninterpretable
     * on shard M. resolveQuadSet / resolveValueSet therefore partition vector
     * ids per producing shard and reverse-resolve them THERE (via
     * Shard.lookUpVectorValues), never through this method. Mingling vector
     * ids from different shards into one resolve call — as the former
     * reverseResolveIds did via policy.vectorShard(id) — is the bug this split
     * fixes; invisible under the trivial single-shard policy (only one shard
     * ever mints vector ids) but fatal once content is split across shards.
     */
    private fun reverseResolveScalarIds(ids: Set<QuadValueId>): Map<QuadValueId, QuadValue> {
        if (ids.isEmpty()) return emptyMap()
        val result = mutableMapOf<QuadValueId, QuadValue>()
        val uris = mutableSetOf<QuadValueId>()
        ids.groupBy { it.first }.forEach { (type, grouped) ->
            when {
                type == AbstractDbStore.DOUBLE_LITERAL_TYPE ->
                    result.putAll(dictionary.lookUpDoubleValues(grouped.map { it.second }.toSet()))
                type == AbstractDbStore.LONG_LITERAL_TYPE ->
                    grouped.associateWith { LongValue(it.second) as QuadValue }.let { result.putAll(it) }
                type == AbstractDbStore.STRING_LITERAL_TYPE ->
                    result.putAll(dictionary.lookUpStringValues(grouped.map { it.second }.toSet()))
                else -> uris.addAll(grouped)
            }
        }
        if (uris.isNotEmpty()) {
            val prefixes = dictionary.lookUpPrefixes(uris.map { it.first }.filter { it != AbstractDbStore.LOCAL_URI_TYPE }.toSet())
            val suffixes = dictionary.lookUpSuffixes(uris.map { it.second }.toSet())
            for (id in uris) {
                val s = suffixes[id.second] ?: continue
                if (id.first == AbstractDbStore.LOCAL_URI_TYPE) {
                    result[id] = LocalQuadValue(s)
                } else {
                    val p = prefixes[id.first] ?: continue
                    result[id] = URIValue(p, s)
                }
            }
        }
        return result
    }

    // ---- routing + fuse ----------------------------------------------------

    /**
     * Partition one filter-operand position into (scalarIds, vectorByShard).
     * Scalar ids resolve through the global dict and broadcast to all shards
     * (see [ShardPolicy.allShards]); vector ids resolve ONLY on their content
     * shard ([ShardPolicy.vectorShard]) and route there exclusively. A vector
     * QuadValueId is shard-local and its discriminator can collide across
     * shards, so broadcasting it would let a foreign shard match a different
     * vector that happens to share (discriminator, localId) -- a silent
     * wrong-value result. Partitioning operands this way prevents that.
     *
     * Returns null for a wildcard (null) position. A non-null return with both
     * maps empty means the position was provided but nothing resolved -- the
     * caller treats that as an empty result, not a wildcard.
     */
    private fun partitionOperand(
        values: Collection<QuadValue>?
    ): Pair<Set<QuadValueId>, Map<Shard, Set<QuadValueId>>>? {
        if (values == null) return null
        val scalarIds = mutableSetOf<QuadValueId>()
        val vectorByShard = mutableMapOf<Shard, MutableSet<QuadValueId>>()
        val scalarValues = ArrayList<QuadValue>()
        val vectorValues = ArrayList<VectorValue>()
        for (v in values) {
            if (v is VectorValue) vectorValues.add(v) else scalarValues.add(v)
        }
        if (scalarValues.isNotEmpty()) {
            val resolved = resolveValuesLookup(scalarValues)
            for (v in scalarValues) resolved[v]?.let { scalarIds.add(it) }
        }
        if (vectorValues.isNotEmpty()) {
            vectorValues.groupBy { policy.vectorShard(it) }.forEach { (shard, group) ->
                if (shard != null) {
                    val ids = shard.lookUpVectorIds(group.toSet())
                    vectorByShard.getOrPut(shard) { mutableSetOf() }.addAll(ids.values)
                }
            }
        }
        return scalarIds to vectorByShard
    }

    /**
     * Fan filter execution across the shards that may hold matching rows:
     * every shard (for broadcast scalar operands) plus the content shards of
     * any vector operands. Each shard receives ONLY the operand ids meaningful
     * on it -- scalar ids (broadcast) and the vector ids it minted. A position
     * that yields no ids on a given shard is empty (match-nothing), not
     * wildcard; the shard's own empty-operand short-circuit handles that.
     * Surviving rows are fused by [resolveQuadSet] (stage-A partition).
     */
    private fun partitionedFanOut(
        sPart: Pair<Set<QuadValueId>, Map<Shard, Set<QuadValueId>>>?,
        pPart: Pair<Set<QuadValueId>, Map<Shard, Set<QuadValueId>>>?,
        oPart: Pair<Set<QuadValueId>, Map<Shard, Set<QuadValueId>>>?
    ): List<Pair<Shard, Set<Triple<QuadValueId, QuadValueId, QuadValueId>>>> {
        val contact = mutableSetOf<Shard>()
        if (sPart != null && sPart.first.isNotEmpty()) contact.addAll(policy.allShards())
        if (pPart != null && pPart.first.isNotEmpty()) contact.addAll(policy.allShards())
        if (oPart != null && oPart.first.isNotEmpty()) contact.addAll(policy.allShards())
        if (sPart != null) contact.addAll(sPart.second.keys)
        if (pPart != null) contact.addAll(pPart.second.keys)
        if (oPart != null) contact.addAll(oPart.second.keys)
        val out = ArrayList<Pair<Shard, Set<Triple<QuadValueId, QuadValueId, QuadValueId>>>>()
        for (shard in contact) {
            val sSet = operandForShard(sPart, shard)
            val pSet = operandForShard(pPart, shard)
            val oSet = operandForShard(oPart, shard)
            if (sSet == null && pSet == null && oSet == null) continue
            val rows = shard.filter(sSet, pSet, oSet)
            if (rows.isNotEmpty()) {
                val tuples = shard.quadTuples(rows).values.toSet()
                if (tuples.isNotEmpty()) out.add(shard to tuples)
            }
        }
        return out
    }

    private fun operandForShard(
        part: Pair<Set<QuadValueId>, Map<Shard, Set<QuadValueId>>>?,
        shard: Shard
    ): Set<QuadValueId>? {
        if (part == null) return null
        val v = part.second[shard]
        return if (v.isNullOrEmpty()) part.first else part.first + v
    }

    /**
     * Fuse per-shard id tuples to value-level quads with PARTITIONED
     * reverse-resolution: scalar ids resolve through the global dict (one
     * batched call across all shards), vector ids resolve on the shard that
     * produced the enclosing tuple (shard-local). See [reverseResolveScalarIds]
     * for why the partition is mandatory. Refuses to construct a quad whose
     * operands could not all be resolved.
     */
    private fun resolveQuadSet(
        shardTuples: List<Pair<Shard, Set<Triple<QuadValueId, QuadValueId, QuadValueId>>>>
    ): QuadSet {
        if (shardTuples.isEmpty()) return BasicQuadSet()
        val scalarIds = mutableSetOf<QuadValueId>()
        val perShardVectors = mutableMapOf<Shard, MutableSet<QuadValueId>>()
        for ((shard, tuples) in shardTuples) {
            if (tuples.isEmpty()) continue
            val victors = perShardVectors.getOrPut(shard) { mutableSetOf() }
            for (t in tuples) {
                for (id in listOf(t.first, t.second, t.third)) {
                    if (id.first < AbstractDbStore.VECTOR_ID_OFFSET) victors.add(id) else scalarIds.add(id)
                }
            }
        }
        val scalars = reverseResolveScalarIds(scalarIds)
        val vectors = mutableMapOf<Shard, Map<QuadValueId, VectorValue>>()
        for ((shard, vids) in perShardVectors) {
            if (vids.isNotEmpty()) vectors[shard] = shard.lookUpVectorValues(vids)
        }
        val quads = mutableSetOf<Quad>()
        for ((shard, tuples) in shardTuples) {
            if (tuples.isEmpty()) continue
            val vm = vectors[shard] ?: emptyMap()
            for (t in tuples) {
                val sv = valueFor(t.first, scalars, vm) ?: continue
                val pv = valueFor(t.second, scalars, vm) ?: continue
                val ov = valueFor(t.third, scalars, vm) ?: continue
                quads.add(Quad(sv, pv, ov))
            }
        }
        return BasicQuadSet(quads)
    }

    private fun valueFor(
        id: QuadValueId,
        scalars: Map<QuadValueId, QuadValue>,
        vectors: Map<QuadValueId, VectorValue>
    ): QuadValue? = if (id.first < AbstractDbStore.VECTOR_ID_OFFSET) vectors[id] else scalars[id]

    /**
     * Distinct-value fuse with the same scalar/vector partition as
     * [resolveQuadSet]: scalar ids via the dict, vector ids via the producing
     * shard. Used by distinctObjects/Subjects, which broadcast across shards
     * and must not mingle vector ids.
     */
    private fun resolveValueSet(shardIds: List<Pair<Shard, Set<QuadValueId>>>): Set<QuadValue> {
        if (shardIds.isEmpty()) return emptySet()
        val scalarIds = mutableSetOf<QuadValueId>()
        val perShardVectors = mutableMapOf<Shard, MutableSet<QuadValueId>>()
        for ((shard, ids) in shardIds) {
            if (ids.isEmpty()) continue
            val victors = perShardVectors.getOrPut(shard) { mutableSetOf() }
            for (id in ids) {
                if (id.first < AbstractDbStore.VECTOR_ID_OFFSET) victors.add(id) else scalarIds.add(id)
            }
        }
        val scalars = reverseResolveScalarIds(scalarIds)
        val vectors = mutableMapOf<Shard, Map<QuadValueId, VectorValue>>()
        for ((shard, vids) in perShardVectors) {
            if (vids.isNotEmpty()) vectors[shard] = shard.lookUpVectorValues(vids)
        }
        val values = mutableSetOf<QuadValue>()
        for ((shard, ids) in shardIds) {
            if (ids.isEmpty()) continue
            val vm = vectors[shard] ?: emptyMap()
            for (id in ids) {
                val v = if (id.first < AbstractDbStore.VECTOR_ID_OFFSET) vm[id] else scalars[id]
                if (v != null) values.add(v)
            }
        }
        return values
    }

    // ---- QuadSet reads -----------------------------------------------------

    override fun filterSubject(subject: QuadValue): QuadSet =
        resolveQuadSet(partitionedFanOut(partitionOperand(setOf(subject)), null, null))

    override fun filterPredicate(predicate: QuadValue): QuadSet =
        resolveQuadSet(partitionedFanOut(null, partitionOperand(setOf(predicate)), null))

    override fun filterObject(`object`: QuadValue): QuadSet =
        resolveQuadSet(partitionedFanOut(null, null, partitionOperand(setOf(`object`))))

    override fun filter(
        subjects: Collection<QuadValue>?,
        predicates: Collection<QuadValue>?,
        objects: Collection<QuadValue>?
    ): QuadSet {
        if (subjects == null && predicates == null && objects == null) {
            TODO("full-scan filter is not supported yet on the cluster backend")
        }
        if (subjects?.isEmpty() == true || predicates?.isEmpty() == true || objects?.isEmpty() == true) return BasicQuadSet()
        val sPart = partitionOperand(subjects)
        val pPart = partitionOperand(predicates)
        val oPart = partitionOperand(objects)
        // Provided-but-unresolved position => nothing can match.
        if (sPart != null && sPart.first.isEmpty() && sPart.second.isEmpty()) return BasicQuadSet()
        if (pPart != null && pPart.first.isEmpty() && pPart.second.isEmpty()) return BasicQuadSet()
        if (oPart != null && oPart.first.isEmpty() && oPart.second.isEmpty()) return BasicQuadSet()
        return resolveQuadSet(partitionedFanOut(sPart, pPart, oPart))
    }

    override fun nearestNeighbor(predicate: QuadValue, `object`: VectorValue, count: Int, distance: Distance, invert: Boolean): QuadSet {
        val pid = resolveValueLookup(predicate) ?: return BasicQuadSet()
        // Gather (shard, localRow, distance) from every shard holding a slice of
        // the corpus, then keep a GLOBAL top-k by distance. Each shard ranks only
        // its own slice; without this merge the union of per-shard top-k is NOT
        // the global top-k (a near candidate on a different shard loses to a
        // farther one locally). Survivor rows partition back to their producing
        // shard for reverse-resolution via the stage-A partitioned fuse.
        //
        // Limitations (acceptable for the parity corpus, documented for later):
        // - Tie-breaking at the k boundary may differ from pgvector's, so ordered
        //   parity holds only when the k-th and (k+1)-th distances are distinct.
        // - Full candidate sort is O(numShards*count); a bounded heap is deferred
        //   (perf, not correctness).
        val candidates = ArrayList<Triple<Shard, Long, Double>>()
        for (shard in policy.nnShards(pid, `object`.type, `object`.length)) {
            for ((row, dist) in shard.nearestNeighborIds(pid, `object`, count, distance, invert)) {
                candidates.add(Triple(shard, row, dist))
            }
        }
        if (candidates.isEmpty()) return BasicQuadSet()
        val selected = if (invert) candidates.sortedByDescending { it.third }.take(count)
                       else candidates.sortedBy { it.third }.take(count)
        val shardRows = mutableMapOf<Shard, MutableSet<Long>>()
        for ((shard, row, _) in selected) {
            shardRows.getOrPut(shard) { mutableSetOf() }.add(row)
        }
        val shardTuples = ArrayList<Pair<Shard, Set<Triple<QuadValueId, QuadValueId, QuadValueId>>>>()
        for ((shard, rows) in shardRows) {
            val tuples = shard.quadTuples(rows).values.toSet()
            if (tuples.isNotEmpty()) shardTuples.add(shard to tuples)
        }
        return resolveQuadSet(shardTuples)
    }

    override fun textFilter(predicate: QuadValue, objectFilterText: String): QuadSet {
        val pid = resolveValueLookup(predicate) ?: return BasicQuadSet()
        val candidates = dictionary.lookUpStringValueIdsByText(objectFilterText)
        if (candidates.isEmpty()) return BasicQuadSet()
        val shardTuples = ArrayList<Pair<Shard, Set<Triple<QuadValueId, QuadValueId, QuadValueId>>>>()
        for (shard in policy.textFilterShards(pid)) {
            val rows = shard.textFilterJoin(pid, candidates)
            if (rows.isNotEmpty()) {
                val tuples = shard.quadTuples(rows).values.toSet()
                if (tuples.isNotEmpty()) shardTuples.add(shard to tuples)
            }
        }
        return resolveQuadSet(shardTuples)
    }

    override fun distinctObjects(predicate: QuadValue): Set<QuadValue> {
        val pid = resolveValueLookup(predicate) ?: return emptySet()
        val shardIds = ArrayList<Pair<Shard, Set<QuadValueId>>>()
        for (shard in policy.distinctShards(pid)) {
            val ids = shard.distinctObjectIds(pid)
            if (ids.isNotEmpty()) shardIds.add(shard to ids)
        }
        return resolveValueSet(shardIds)
    }

    override fun distinctSubjects(predicate: QuadValue): Set<QuadValue> {
        val pid = resolveValueLookup(predicate) ?: return emptySet()
        val shardIds = ArrayList<Pair<Shard, Set<QuadValueId>>>()
        for (shard in policy.distinctShards(pid)) {
            val ids = shard.distinctSubjectIds(pid)
            if (ids.isNotEmpty()) shardIds.add(shard to ids)
        }
        return resolveValueSet(shardIds)
    }

    // ---- writes ------------------------------------------------------------

    override fun add(element: Quad): Boolean {
        val (sid, pid, oid) = addOperands(element) ?: return false
        // Co-location: a vector-operand quad is placed on the vector content's
        // owning shard (the only shard that can mint/resolve its id); the
        // dup-check is definite there. A scalar quad's dup-check is definite
        // ONLY when the policy names a definite holder -- a non-deterministic
        // placement (e.g. round-robin) returns null and the dup-check
        // BROADCASTS across all shards, so an add of an already-stored quad
        // placed on a prior counter shard is still detected. The insert then
        // goes to the policy's placement target (addShard), which for RR is
        // the next counter shard -- distinct from the broadcast-checked set,
        // but the broadcast covered all shards including that target.
        if (isVectorOperand(element)) {
            val owner = vectorOwner(element)
            if (owner.quadId(sid, pid, oid) != null) return false
            owner.addQuad(sid, pid, oid, element.id.toString())
            return true
        }
        if (dupExistsScalar(element.subject, sid, pid, oid)) return false
        policy.addShard(element.subject, sid, pid, oid).addQuad(sid, pid, oid, element.id.toString())
        return true
    }

    override fun getId(id: SemanticId): Quad? {
        // Broadcast: the semantic id is content-based, so at most one distinct
        // quad matches across shards. Each shard returns the id-tuple of its
        // local row (if any); reverse resolution to a Quad is centralized here
        // via resolveQuadSet (scalars via the dict, vectors via the owning
        // shard). First hit wins.
        for (shard in policy.allShards()) {
            val tuple = shard.getId(id) ?: continue
            return resolveQuadSet(listOf(shard to setOf(tuple))).firstOrNull()
        }
        return null
    }

    override fun contains(element: Quad): Boolean {
        val s = resolveValueLookup(element.subject) ?: return false
        val p = resolveValueLookup(element.predicate) ?: return false
        val o = resolveValueLookup(element.`object`) ?: return false
        if (isVectorOperand(element)) {
            return vectorOwner(element).quadId(s, p, o) != null
        }
        val definite = policy.definiteShard(element.subject, s, p, o)
        if (definite != null) return definite.quadId(s, p, o) != null
        return anyShardHolds(s, p, o)
    }

    private fun isVectorOperand(quad: Quad): Boolean =
        quad.subject is VectorValue || quad.predicate is VectorValue || quad.`object` is VectorValue

    /**
     * Owning shard for the (single) vector operand of [quad]; throws if no
     * shard owns that vector's content. Mirrors the former routeAdd contract.
     * Precondition: [isVectorOperand].
     */
    private fun vectorOwner(quad: Quad): Shard {
        for (v in listOf(quad.subject, quad.predicate, quad.`object`)) {
            if (v is VectorValue) {
                return policy.vectorShard(v)
                    ?: throw IllegalStateException("no shard owns vector (${v.type}, ${v.length}); cannot place quad")
            }
        }
        throw IllegalStateException("vectorOwner called on a quad with no vector operand")
    }

    /**
     * Scalar dup-probe: definite holder if the policy names one, else
     * broadcast across [ShardPolicy.allShards]. The broadcast is the cost of
     * non-deterministic placement (round-robin): every add of a scalar quad
     * touches every shard to guarantee the add contract (true iff newly
     * stored) despite placement that rotates across shards.
     */
    private fun dupExistsScalar(subjectValue: QuadValue, s: QuadValueId, p: QuadValueId, o: QuadValueId): Boolean {
        val definite = policy.definiteShard(subjectValue, s, p, o)
        if (definite != null) return definite.quadId(s, p, o) != null
        return anyShardHolds(s, p, o)
    }

    private fun anyShardHolds(s: QuadValueId, p: QuadValueId, o: QuadValueId): Boolean {
        for (shard in policy.allShards()) {
            if (shard.quadId(s, p, o) != null) return true
        }
        return false
    }

    private fun addOperands(quad: Quad): Triple<QuadValueId, QuadValueId, QuadValueId>? {
        // Co-location: a quad with vector operands must route to the corpus owner.
        // Two distinct vector corpora among the operands is unplaceable.
        val vectorCorp = mutableSetOf<Pair<VectorValue.Type, Int>>()
        for (v in listOf(quad.subject, quad.predicate, quad.`object`)) {
            if (v is VectorValue) vectorCorp.add(v.type to v.length)
        }
        if (vectorCorp.size > 1) {
            throw IllegalStateException("quad has operands from ${vectorCorp.size} distinct vector corpora; a quad can co-locate with at most one corpus")
        }
        val ids = resolveValuesAdd(listOf(quad.subject, quad.predicate, quad.`object`))
        val s = ids[quad.subject] ?: return null
        val p = ids[quad.predicate] ?: return null
        val o = ids[quad.`object`] ?: return null
        return Triple(s, p, o)
    }

    override fun addAll(elements: Collection<Quad>): Boolean = elements.any { add(it) }

    override fun containsAll(elements: Collection<Quad>): Boolean = elements.all { contains(it) }

    // ---- full-scan ops (best-effort / TODO) ---------------------------------

    override fun toMutable(): MutableQuadSet = this

    override fun toSet(): Set<Quad> = TODO("full scan (toSet) not yet supported on the cluster backend")

    override fun plus(other: QuadSet): QuadSet = TODO("plus not yet supported on the cluster backend")

    override val size: Int get() = TODO("size requires a full scan; not yet supported on the cluster backend")
    override fun isEmpty(): Boolean = TODO("isEmpty requires a full scan; not yet supported on the cluster backend")
    override fun iterator(): MutableIterator<Quad> = TODO("iteration requires a full scan; not yet supported on the cluster backend")

    override fun clear() = TODO("clear not yet supported on the cluster backend")
    override fun remove(element: Quad): Boolean = TODO("remove not yet supported on the cluster backend")
    override fun removeAll(elements: Collection<Quad>): Boolean = TODO("remove not yet supported on the cluster backend")
    override fun retainAll(elements: Collection<Quad>): Boolean = TODO("retainAll not yet supported on the cluster backend")
}
