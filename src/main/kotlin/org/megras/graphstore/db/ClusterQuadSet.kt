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

    override fun getId(id: Long): Quad? = throw UnsupportedOperationException(
        "getId by row id is not meaningful in a distributed backend; a bare row Long is ambiguous across shards. " +
            "Pending the quad semantic-id redesign (content-hash id), use value-level filter ops instead."
    )

    // ---- QuadValue <-> ID (no caches) --------------------------------------

    private fun owningShardFor(value: VectorValue) = policy.vectorShard(value.type, value.length)

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
        is DoubleValue -> dictionary.insertDoubleValues(setOf(value))[value]
        is StringValue -> dictionary.insertStringValues(setOf(value))[value]
        is LongValue -> QuadValueId(AbstractDbStore.LONG_LITERAL_TYPE, value.value)
        is LocalQuadValue -> {
            val suffix = dictionary.insertSuffixValues(setOf(value.suffix()))[value.suffix()]!!
            QuadValueId(AbstractDbStore.LOCAL_URI_TYPE, suffix)
        }
        is URIValue -> {
            val prefix = dictionary.insertPrefixValues(setOf(value.prefix()))[value.prefix()]!!
            val suffix = dictionary.insertSuffixValues(setOf(value.suffix()))[value.suffix()]!!
            QuadValueId(prefix, suffix)
        }
        is VectorValue -> owningShardFor(value)?.insertVectorIds(setOf(value))?.get(value)
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
            vectors.groupBy { it.type to it.length }.forEach { (props, group) ->
                policy.vectorShard(props.first, props.second)?.let {
                    result.putAll(it.lookUpVectorIds(group.toSet()))
                }
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
        if (doubles.isNotEmpty()) result.putAll(dictionary.insertDoubleValues(doubles))
        if (strings.isNotEmpty()) result.putAll(dictionary.insertStringValues(strings))
        if (uris.isNotEmpty()) {
            val prefixes = mutableMapOf<String, Int>()
            val suffixes = mutableMapOf<String, Long>()
            val pVals = uris.filter { it !is LocalQuadValue }.map { it.prefix() }.toSet()
            val sVals = uris.map { it.suffix() }.toSet()
            if (pVals.isNotEmpty()) prefixes.putAll(dictionary.insertPrefixValues(pVals))
            if (sVals.isNotEmpty()) suffixes.putAll(dictionary.insertSuffixValues(sVals))
            for (u in uris) {
                val p = if (u is LocalQuadValue) AbstractDbStore.LOCAL_URI_TYPE else prefixes[u.prefix()]
                val s = suffixes[u.suffix()]
                if (p != null && s != null) result[u] = QuadValueId(p, s)
            }
        }
        if (vectors.isNotEmpty()) {
            vectors.groupBy { it.type to it.length }.forEach { (props, group) ->
                policy.vectorShard(props.first, props.second)?.let {
                    result.putAll(it.insertVectorIds(group.toSet()))
                }
            }
        }
        return result
    }

    private fun reverseResolveIds(ids: Set<QuadValueId>): Map<QuadValueId, QuadValue> {
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
                type < AbstractDbStore.VECTOR_ID_OFFSET -> {
                    policy.vectorShard(grouped.first())?.let {
                        result.putAll(it.lookUpVectorValues(grouped.toSet()))
                    }
                }
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

    private fun routeFilter(
        s: Collection<QuadValueId>?,
        p: Collection<QuadValueId>?,
        o: Collection<QuadValueId>?
    ): Set<Triple<QuadValueId, QuadValueId, QuadValueId>> {
        val triples = mutableSetOf<Triple<QuadValueId, QuadValueId, QuadValueId>>()
        for (shard in policy.filterShards(s, p, o)) {
            val localRows = shard.filter(s, p, o)
            if (localRows.isNotEmpty()) {
                shard.quadTuples(localRows).values.let { triples.addAll(it) }
            }
        }
        return triples
    }

    private fun buildQuadSet(triples: Set<Triple<QuadValueId, QuadValueId, QuadValueId>>): QuadSet {
        if (triples.isEmpty()) return BasicQuadSet()
        val ids = mutableSetOf<QuadValueId>()
        for (t in triples) { ids.add(t.first); ids.add(t.second); ids.add(t.third) }
        val values = reverseResolveIds(ids)
        val quads = mutableSetOf<Quad>()
        for (t in triples) {
            val sv = values[t.first] ?: continue
            val pv = values[t.second] ?: continue
            val ov = values[t.third] ?: continue
            quads.add(Quad(null, sv, pv, ov))
        }
        return BasicQuadSet(quads)
    }

    private fun buildValueSet(ids: Set<QuadValueId>): Set<QuadValue> {
        if (ids.isEmpty()) return emptySet()
        return reverseResolveIds(ids).values.toSet()
    }

    // ---- QuadSet reads -----------------------------------------------------

    override fun filterSubject(subject: QuadValue): QuadSet {
        val sid = resolveValueLookup(subject) ?: return BasicQuadSet()
        return buildQuadSet(routeFilter(setOf(sid), null, null))
    }

    override fun filterPredicate(predicate: QuadValue): QuadSet {
        val pid = resolveValueLookup(predicate) ?: return BasicQuadSet()
        return buildQuadSet(routeFilter(null, setOf(pid), null))
    }

    override fun filterObject(`object`: QuadValue): QuadSet {
        val oid = resolveValueLookup(`object`) ?: return BasicQuadSet()
        return buildQuadSet(routeFilter(null, null, setOf(oid)))
    }

    override fun filter(
        subjects: Collection<QuadValue>?,
        predicates: Collection<QuadValue>?,
        objects: Collection<QuadValue>?
    ): QuadSet {
        if (subjects == null && predicates == null && objects == null) {
            TODO("full-scan filter is not supported yet on the cluster backend")
        }
        if (subjects?.isEmpty() == true || predicates?.isEmpty() == true || objects?.isEmpty() == true) return BasicQuadSet()
        val all = (subjects ?: emptySet()) + (predicates ?: emptySet()) + (objects ?: emptySet())
        val resolved = resolveValuesLookup(all)
        val sIds = subjects?.mapNotNull { resolved[it] }
        val pIds = predicates?.mapNotNull { resolved[it] }
        val oIds = objects?.mapNotNull { resolved[it] }
        if ((subjects != null && sIds!!.isEmpty()) || (predicates != null && pIds!!.isEmpty()) || (objects != null && oIds!!.isEmpty())) {
            return BasicQuadSet()
        }
        return buildQuadSet(routeFilter(sIds, pIds, oIds))
    }

    override fun nearestNeighbor(predicate: QuadValue, `object`: VectorValue, count: Int, distance: Distance, invert: Boolean): QuadSet {
        val pid = resolveValueLookup(predicate) ?: return BasicQuadSet()
        val triples = mutableSetOf<Triple<QuadValueId, QuadValueId, QuadValueId>>()
        for (shard in policy.nnShards(pid, `object`.type, `object`.length)) {
            val rows = shard.nearestNeighborIds(pid, `object`, count, distance, invert)
            if (rows.isNotEmpty()) shard.quadTuples(rows).values.let { triples.addAll(it) }
        }
        return buildQuadSet(triples)
    }

    override fun textFilter(predicate: QuadValue, objectFilterText: String): QuadSet {
        val pid = resolveValueLookup(predicate) ?: return BasicQuadSet()
        val candidates = dictionary.lookUpStringValueIdsByText(objectFilterText)
        if (candidates.isEmpty()) return BasicQuadSet()
        val triples = mutableSetOf<Triple<QuadValueId, QuadValueId, QuadValueId>>()
        for (shard in policy.textFilterShards(pid)) {
            val rows = shard.textFilterJoin(pid, candidates)
            if (rows.isNotEmpty()) shard.quadTuples(rows).values.let { triples.addAll(it) }
        }
        return buildQuadSet(triples)
    }

    override fun distinctObjects(predicate: QuadValue): Set<QuadValue> {
        val pid = resolveValueLookup(predicate) ?: return emptySet()
        val ids = mutableSetOf<QuadValueId>()
        for (shard in policy.distinctShards(pid)) ids.addAll(shard.distinctObjectIds(pid))
        return buildValueSet(ids)
    }

    override fun distinctSubjects(predicate: QuadValue): Set<QuadValue> {
        val pid = resolveValueLookup(predicate) ?: return emptySet()
        val ids = mutableSetOf<QuadValueId>()
        for (shard in policy.distinctShards(pid)) ids.addAll(shard.distinctSubjectIds(pid))
        return buildValueSet(ids)
    }

    // ---- writes ------------------------------------------------------------

    override fun add(element: Quad): Boolean {
        val (sid, pid, oid) = addOperands(element) ?: return false
        val target = routeAdd(element, sid, pid, oid)
        if (target.quadId(sid, pid, oid) != null) return false
        target.addQuad(sid, pid, oid)
        return true
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

    private fun routeAdd(quad: Quad, s: QuadValueId, p: QuadValueId, o: QuadValueId): Shard {
        for (v in listOf(quad.subject, quad.predicate, quad.`object`)) {
            if (v is VectorValue) return policy.vectorShard(v.type, v.length)
                ?: throw IllegalStateException("no shard owns vector corpus (${v.type}, ${v.length}); cannot place quad")
        }
        return policy.addShard(s, p, o)
    }

    override fun addAll(elements: Collection<Quad>): Boolean = elements.any { add(it) }

    override fun contains(element: Quad): Boolean {
        val s = resolveValueLookup(element.subject) ?: return false
        val p = resolveValueLookup(element.predicate) ?: return false
        val o = resolveValueLookup(element.`object`) ?: return false
        return routeAdd(element, s, p, o).quadId(s, p, o) != null
    }

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
