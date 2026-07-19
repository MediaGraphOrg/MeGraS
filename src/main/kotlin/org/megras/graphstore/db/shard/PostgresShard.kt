package org.megras.graphstore.db.shard

import com.google.common.cache.CacheBuilder
import com.pgvector.PGvector
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.Schema
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.megras.data.graph.DoubleVectorValue
import org.megras.data.graph.FloatVectorValue
import org.megras.data.graph.LongVectorValue
import org.megras.data.graph.VectorValue
import org.megras.graphstore.Distance
import org.megras.graphstore.db.AbstractDbStore
import org.megras.graphstore.db.QuadValueId
import org.megras.id.SemanticId
import org.megras.util.extensions.toBase64
import java.nio.ByteBuffer

/**
 * ID-level Postgres shard: stores quad rows by (QuadValueId, QuadValueId,
 * QuadValueId) tuple and owns vector content for corpora it has created.
 * Implements [Shard]; does NOT speak QuadValue — translating QuadValue <->
 * ID (scalar via the central [org.megras.graphstore.db.dict.QuadValueDictionary],
 * vector via this shard) is [ClusterQuadSet]'s job. Storage code is duplicated
 * from PostgresStore by design (decision: separate, duplicate SQL) rather than
 * refactoring the just-verified single-node path.
 *
 * Uses the explicit `transaction(shardDb)` form, never ambient, so a process
 * holding a dict Database and multiple shard Databases is never ambiguous about
 * which pool an ambient transaction would bind to.
 *
 * Row ids are bare Long internally; [ClusterQuadSet] composites them with this
 * shard's ordinal for any cross-shard use. Type-discriminator constants (incl.
 * VECTOR_ID_OFFSET) are referenced from [AbstractDbStore] as the single source
 * of the shared encoding scheme.
 */
class PostgresShard(
    host: String = "localhost:5432/megras",
    user: String = "megras",
    password: String = "megras"
) : Shard {

    private val shardDb: Database

    // Vector value<->id caches live HERE, on the owning shard, not in callers:
    // vector content is sharded by corpus (this shard owns a subset), so — like
    // the scalar caches on PostgresDictionary — the cache must sit at the leaf
    // boundary the Shard interface exposes, or ClusterQuadSet would have to
    // duplicate it per call site. Count-bound at AbstractDbStore.cacheSize to
    // match the existing single-node PostgresStore vector-cache policy (no
    // byte-weight divergence, deliberately: footprint tuning is a separate
    // orthogonal decision that should affect both sides equally).
    private val vectorIdCache = CacheBuilder.newBuilder().maximumSize(AbstractDbStore.cacheSize).build<VectorValue, QuadValueId>()
    private val vectorValueCache = CacheBuilder.newBuilder().maximumSize(AbstractDbStore.cacheSize).build<QuadValueId, VectorValue>()

    init {
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:postgresql://$host"
            driverClassName = "org.postgresql.Driver"
            username = user
            this.password = password
            maximumPoolSize = 10
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            maxLifetime = 1680000
            idleTimeout = 600000
        }
        shardDb = Database.connect(HikariDataSource(config))
        transaction(shardDb) {
            val schema = Schema("megras")
            SchemaUtils.createSchema(schema)
            SchemaUtils.setSchema(schema)
        }
    }

    object QuadsTable : Table("quads") {
        val id: Column<Long> = long("id").autoIncrement().uniqueIndex()
        val sType: Column<Int> = integer("s_type").index()
        val s: Column<Long> = long("s").index()
        val pType: Column<Int> = integer("p_type").index()
        val p: Column<Long> = long("p").index()
        val oType: Column<Int> = integer("o_type").index()
        val o: Column<Long> = long("o").index()
        val hash: Column<String> = varchar("hash", 48).uniqueIndex()
        val semid: Column<String> = varchar("semid", 64).uniqueIndex()

        override val primaryKey = PrimaryKey(id)

        val sIndex = index(false, sType, s)
        val pIndex = index(false, p, pType)
        val oIndex = index(false, oType, o)

        val spIndex = index(false, sType, s, pType, p)
        val poIndex = index(false, pType, p, oType, o)
    }

    object VectorTypesTable : Table("vector_types") {
        val id: Column<Int> = integer("id").autoIncrement().uniqueIndex()
        val type: Column<Int> = integer("type")
        val length: Column<Int> = integer("length")

        override val primaryKey = PrimaryKey(id)
    }

    override fun setup() {
        transaction(shardDb) {
            SchemaUtils.create(QuadsTable, VectorTypesTable)
            exec("CREATE EXTENSION IF NOT EXISTS vector;")
        }
    }

    private fun quadHash(sType: Int, s: Long, pType: Int, p: Long, oType: Int, o: Long): String {
        val buf = ByteBuffer.wrap(ByteArray(36))
        buf.putInt(sType)
        buf.putLong(s)
        buf.putInt(pType)
        buf.putLong(p)
        buf.putInt(oType)
        buf.putLong(o)
        return buf.array().toBase64()
    }

    override fun addQuad(s: QuadValueId, p: QuadValueId, o: QuadValueId, semid: String): Long {
        quadId(s, p, o)?.let { return it }
        return transaction(shardDb) {
            QuadsTable.insert {
                it[sType] = s.first
                it[this.s] = s.second
                it[pType] = p.first
                it[this.p] = p.second
                it[oType] = o.first
                it[this.o] = o.second
                it[hash] = quadHash(s.first, s.second, p.first, p.second, o.first, o.second)
                it[this.semid] = semid
            }[QuadsTable.id]
        }
    }

    override fun quadId(s: QuadValueId, p: QuadValueId, o: QuadValueId): Long? =
        transaction(shardDb) {
            QuadsTable.select(QuadsTable.id)
                .where { QuadsTable.hash eq quadHash(s.first, s.second, p.first, p.second, o.first, o.second) }
                .firstOrNull()?.get(QuadsTable.id)
        }

    override fun getId(id: SemanticId): Triple<QuadValueId, QuadValueId, QuadValueId>? =
        transaction(shardDb) {
            QuadsTable.select(QuadsTable.sType, QuadsTable.s, QuadsTable.pType, QuadsTable.p, QuadsTable.oType, QuadsTable.o)
                .where { QuadsTable.semid eq id.toString() }.firstOrNull()?.let {
                    Triple(it[QuadsTable.sType] to it[QuadsTable.s], it[QuadsTable.pType] to it[QuadsTable.p], it[QuadsTable.oType] to it[QuadsTable.o])
                }
        }

    override fun quadTuples(rowIds: Set<Long>): Map<Long, Triple<QuadValueId, QuadValueId, QuadValueId>> {
        if (rowIds.isEmpty()) return emptyMap()
        val result = mutableMapOf<Long, Triple<QuadValueId, QuadValueId, QuadValueId>>()
        rowIds.chunked(10000).forEach { chunk ->
            transaction(shardDb) {
                QuadsTable.select(QuadsTable.id, QuadsTable.sType, QuadsTable.s, QuadsTable.pType, QuadsTable.p, QuadsTable.oType, QuadsTable.o)
                    .where { QuadsTable.id inList chunk }
                    .forEach {
                        result[it[QuadsTable.id]] = Triple(
                            it[QuadsTable.sType] to it[QuadsTable.s],
                            it[QuadsTable.pType] to it[QuadsTable.p],
                            it[QuadsTable.oType] to it[QuadsTable.o],
                        )
                    }
            }
        }
        return result
    }

    override fun filter(
        s: Collection<QuadValueId>?,
        p: Collection<QuadValueId>?,
        o: Collection<QuadValueId>?
    ): Set<Long> {
        if (s == null && p == null && o == null) {
            throw UnsupportedOperationException("full-scan filter (all operands null) is not supported at the shard level; ClusterQuadSet handles it best-effort")
        }
        if (s?.isEmpty() == true || p?.isEmpty() == true || o?.isEmpty() == true) return emptySet()
        return transaction(shardDb) {
            var condition: Op<Boolean> = Op.TRUE
            if (s != null) {
                condition = condition and s.map { (QuadsTable.sType eq it.first) and (QuadsTable.s eq it.second) }
                    .reduce { acc, x -> acc or x }
            }
            if (p != null) {
                condition = condition and p.map { (QuadsTable.pType eq it.first) and (QuadsTable.p eq it.second) }
                    .reduce { acc, x -> acc or x }
            }
            if (o != null) {
                condition = condition and o.map { (QuadsTable.oType eq it.first) and (QuadsTable.o eq it.second) }
                    .reduce { acc, x -> acc or x }
            }
            // TODO(substrate-perf): port the VALUES-clause path from PostgresStore
            // for filter sets above the OR-chain threshold; large OR chains are
            // slow in Postgres. Correctness holds without it; performance does not.
            QuadsTable.select(QuadsTable.id).where(condition).mapTo(mutableSetOf()) { it[QuadsTable.id] }
        }
    }

    override fun distinctObjectIds(predicate: QuadValueId): Set<QuadValueId> =
        transaction(shardDb) {
            QuadsTable.select(QuadsTable.oType, QuadsTable.o)
                .where { (QuadsTable.pType eq predicate.first) and (QuadsTable.p eq predicate.second) }
                .withDistinct()
                .map { it[QuadsTable.oType] to it[QuadsTable.o] }
                .toSet()
        }

    override fun distinctSubjectIds(predicate: QuadValueId): Set<QuadValueId> =
        transaction(shardDb) {
            QuadsTable.select(QuadsTable.sType, QuadsTable.s)
                .where { (QuadsTable.pType eq predicate.first) and (QuadsTable.p eq predicate.second) }
                .withDistinct()
                .map { it[QuadsTable.sType] to it[QuadsTable.s] }
                .toSet()
        }

    override fun textFilterJoin(predicate: QuadValueId, stringCandidateIds: Set<Long>): Set<Long> {
        if (stringCandidateIds.isEmpty()) return emptySet()
        val result = mutableSetOf<Long>()
        stringCandidateIds.chunked(10000).forEach { chunk ->
            transaction(shardDb) {
                result.addAll(
                    QuadsTable.select(QuadsTable.id)
                        .where {
                            (QuadsTable.pType eq predicate.first) and (QuadsTable.p eq predicate.second) and
                                (QuadsTable.oType eq AbstractDbStore.STRING_LITERAL_TYPE) and (QuadsTable.o inList chunk)
                        }
                        .map { it[QuadsTable.id] }
                )
            }
        }
        return result
    }

    override fun nearestNeighborIds(
        predicate: QuadValueId,
        query: VectorValue,
        count: Int,
        distance: Distance,
        invert: Boolean
    ): List<Pair<Long, Double>> {
        val vectorTable = getVectorTable(query.type, query.length) ?: return emptyList()
        val distanceExpression = VectorDistance(vectorTable.value, query, distance)
        return transaction(shardDb) {
            QuadsTable.join(
                vectorTable,
                JoinType.INNER,
                onColumn = QuadsTable.o,
                otherColumn = vectorTable.id
            ) {
                (QuadsTable.oType eq (-vectorTable.typeId + AbstractDbStore.VECTOR_ID_OFFSET))
            }
                .select(QuadsTable.id, distanceExpression)
                .where {
                    (QuadsTable.pType eq predicate.first) and (QuadsTable.p eq predicate.second)
                }
                .orderBy(distanceExpression to if (invert) SortOrder.DESC else SortOrder.ASC)
                .limit(count)
                .map { it[QuadsTable.id] to it[distanceExpression] }
        }
    }

    override fun ownsVectorCorpus(type: VectorValue.Type, length: Int): Boolean =
        getVectorTable(type, length) != null

    // ---- vector content -----------------------------------------------------

    override fun insertVectorIds(vectorValues: Set<VectorValue>): Map<VectorValue, QuadValueId> {
        if (vectorValues.isEmpty()) return emptyMap()
        return vectorValues.groupBy { it.type to it.length }.map { (properties, v) ->
            val currentVectorType = properties.first
            val currentLength = properties.second
            val vectorTable = getOrCreateVectorTable(currentVectorType, currentLength)
            transaction(shardDb) {
                v.map { vec ->
                    val id = vectorTable.insertReturning {
                        when (currentVectorType) {
                            VectorValue.Type.Float -> {
                                @Suppress("UNCHECKED_CAST")
                                it[vectorTable.value as Column<FloatVectorValue>] = vec as FloatVectorValue
                            }
                            VectorValue.Type.Double -> {
                                @Suppress("UNCHECKED_CAST")
                                it[vectorTable.value as Column<DoubleVectorValue>] = vec as DoubleVectorValue
                            }
                            VectorValue.Type.Long -> {
                                @Suppress("UNCHECKED_CAST")
                                it[vectorTable.value as Column<LongVectorValue>] = vec as LongVectorValue
                            }
                        }
                    }.single()[vectorTable.id]
                    val qid = QuadValueId(-vectorTable.typeId + AbstractDbStore.VECTOR_ID_OFFSET, id)
                    vectorIdCache.put(vec, qid)
                    vectorValueCache.put(qid, vec)
                    vec to qid
                }
            }
        }.flatten().toMap()
    }

    override fun lookUpVectorIds(vectorValues: Set<VectorValue>): Map<VectorValue, QuadValueId> {
        if (vectorValues.isEmpty()) return emptyMap()
        val returnMap = HashMap<VectorValue, QuadValueId>(vectorValues.size)
        vectorValues.groupBy { it.type to it.length }.forEach { (properties, vectorList) ->
            val uncached = ArrayList<VectorValue>(vectorList.size)
            for (vec in vectorList) {
                val cached = vectorIdCache.getIfPresent(vec)
                if (cached != null) returnMap[vec] = cached else uncached.add(vec)
            }
            if (uncached.isEmpty()) return@forEach
            val vectorsInGroup = uncached.toSet()
            val vectorTable = getOrCreateVectorTable(properties.first, properties.second)
            transaction(shardDb) {
                when (properties.first) {
                    VectorValue.Type.Float -> {
                        @Suppress("UNCHECKED_CAST")
                        val col = vectorTable.value as Column<FloatVectorValue>
                        val floats = vectorsInGroup.filterIsInstance<FloatVectorValue>()
                        if (floats.isNotEmpty()) {
                            floats.chunked(100).forEach { chunk ->
                                vectorTable.selectAll().where { col inList chunk }
                                    .forEach {
                                        val qid = QuadValueId(-vectorTable.typeId + AbstractDbStore.VECTOR_ID_OFFSET, it[vectorTable.id])
                                        val value = it[col]
                                        returnMap[value] = qid
                                        vectorIdCache.put(value, qid)
                                        vectorValueCache.put(qid, value)
                                    }
                            }
                        }
                    }
                    VectorValue.Type.Double -> {
                        @Suppress("UNCHECKED_CAST")
                        val col = vectorTable.value as Column<DoubleVectorValue>
                        val doubles = vectorsInGroup.filterIsInstance<DoubleVectorValue>()
                        if (doubles.isNotEmpty()) {
                            doubles.chunked(10000).forEach { chunk ->
                                vectorTable.selectAll().where { col inList chunk }
                                    .forEach {
                                        val qid = QuadValueId(-vectorTable.typeId + AbstractDbStore.VECTOR_ID_OFFSET, it[vectorTable.id])
                                        val value = it[col]
                                        returnMap[value] = qid
                                        vectorIdCache.put(value, qid)
                                        vectorValueCache.put(qid, value)
                                    }
                            }
                        }
                    }
                    VectorValue.Type.Long -> {
                        @Suppress("UNCHECKED_CAST")
                        val col = vectorTable.value as Column<LongVectorValue>
                        val longs = vectorsInGroup.filterIsInstance<LongVectorValue>()
                        if (longs.isNotEmpty()) {
                            longs.chunked(10000).forEach { chunk ->
                                vectorTable.selectAll().where { col inList chunk }
                                    .forEach {
                                        val qid = QuadValueId(-vectorTable.typeId + AbstractDbStore.VECTOR_ID_OFFSET, it[vectorTable.id])
                                        val value = it[col]
                                        returnMap[value] = qid
                                        vectorIdCache.put(value, qid)
                                        vectorValueCache.put(qid, value)
                                    }
                            }
                        }
                    }
                }
            }
        }
        return returnMap
    }

    override fun lookUpVectorValues(ids: Set<QuadValueId>): Map<QuadValueId, VectorValue> {
        if (ids.isEmpty()) return emptyMap()
        val returnMap = HashMap<QuadValueId, VectorValue>(ids.size)
        val uncached = ArrayList<QuadValueId>(ids.size)
        for (id in ids) {
            val cached = vectorValueCache.getIfPresent(id)
            if (cached != null) returnMap[id] = cached else uncached.add(id)
        }
        if (uncached.isEmpty()) return returnMap
        uncached.groupBy { it.first }.forEach { (type, quadValueIds) ->
            val longIds = quadValueIds.map { it.second }
            longIds.chunked(10000).forEach { chunk ->
                val values = getVectorQuadValues(type, chunk)
                values.forEach { (longId, vectorValue) ->
                    val qid = type to longId
                    returnMap[qid] = vectorValue
                    vectorValueCache.put(qid, vectorValue)
                    vectorIdCache.put(vectorValue, qid)
                }
            }
        }
        return returnMap
    }

    private fun getVectorQuadValues(type: Int, ids: List<Long>): Map<Long, VectorValue> {
        if (ids.isEmpty()) return emptyMap()
        val internalId = -type + AbstractDbStore.VECTOR_ID_OFFSET
        val properties = getVectorProperties(internalId) ?: return emptyMap()
        val vectorTable = getOrCreateVectorTable(properties.second, properties.first)
        val result = mutableMapOf<Long, Any>()
        ids.chunked(10000).forEach { chunk ->
            transaction(shardDb) {
                vectorTable.selectAll().where { vectorTable.id inList chunk }
                    .forEach { result[it[vectorTable.id]] = it[vectorTable.value] }
            }
        }
        val map = mutableMapOf<Long, VectorValue>()
        when (properties.second) {
            VectorValue.Type.Double -> result.forEach { (id, value) -> map[id] = value as DoubleVectorValue }
            VectorValue.Type.Long -> result.forEach { (id, value) -> map[id] = value as LongVectorValue }
            VectorValue.Type.Float -> result.forEach { (id, value) -> map[id] = value as FloatVectorValue }
        }
        return map
    }

    private fun getVectorProperties(type: Int): Pair<Int, VectorValue.Type>? =
        transaction(shardDb) {
            VectorTypesTable.selectAll()
                .where { VectorTypesTable.id eq type }
                .firstOrNull()
                ?.let { row -> row[VectorTypesTable.length] to VectorValue.Type.values()[row[VectorTypesTable.type]] }
        }

    private fun getOrCreateVectorTable(type: VectorValue.Type, length: Int): VectorTable {
        fun createTable(): VectorTable {
            val result = transaction(shardDb) {
                VectorTypesTable.insert {
                    it[VectorTypesTable.type] = type.ordinal
                    it[VectorTypesTable.length] = length
                }
            }
            val id = result[VectorTypesTable.id]
            val vectorTable = VectorTable(id, type, length)
            transaction(shardDb) {
                SchemaUtils.create(vectorTable)
                exec("CREATE INDEX IF NOT EXISTS vector_values_${id}_hnsw_idx ON megras.vector_values_${id} USING hnsw (value vector_cosine_ops) WITH (m = 16, ef_construction = 64)")
            }
            return vectorTable
        }
        return getVectorTable(type, length) ?: createTable()
    }

    private fun getVectorTable(type: VectorValue.Type, length: Int): VectorTable? =
        transaction(shardDb) {
            VectorTypesTable.selectAll().where {
                (VectorTypesTable.type eq type.ordinal) and (VectorTypesTable.length eq length)
            }.map { it[VectorTypesTable.id] }.firstOrNull()
        }?.let { VectorTable(it, type, length) }

    private class VectorDistance(
        private val column: Expression<*>,
        private val target: VectorValue,
        private val distance: Distance
    ) : Op<Double>() {
        override fun toQueryBuilder(queryBuilder: QueryBuilder) {
            val op = when (distance) {
                Distance.COSINE -> "<=>"
                Distance.DOTPRODUCT -> "<#>"
            }
            val pgVector = when (target) {
                is FloatVectorValue -> PGvector(target.vector)
                is DoubleVectorValue -> PGvector(target.vector.map { it.toFloat() }.toFloatArray())
                is LongVectorValue -> PGvector(target.vector.map { it.toFloat() }.toFloatArray())
                else -> throw IllegalArgumentException("unsupported vector type: ${target.type}")
            }
            queryBuilder.append("(")
            column.toQueryBuilder(queryBuilder)
            queryBuilder.append(" $op ")
            queryBuilder.append(stringLiteral(pgVector.toString()))
            queryBuilder.append(")")
        }
    }

    class VectorTable(val typeId: Int, type: VectorValue.Type, length: Int) : Table("vector_values_$typeId") {
        val id: Column<Long> = long("id").autoIncrement().uniqueIndex()
        val value = when (type) {
            VectorValue.Type.Float -> registerColumn<FloatVectorValue>("value",
                object : IColumnType<FloatVectorValue> {
                    override var nullable = false
                    override fun sqlType(): String = "vector($length)"
                    override fun valueFromDB(value: Any): FloatVectorValue = FloatVectorValue.parse(value.toString())
                    override fun notNullValueToDB(value: FloatVectorValue): Any = PGvector((value as FloatVectorValue).vector)
                }
            )
            VectorValue.Type.Double -> registerColumn<DoubleVectorValue>("value",
                object : IColumnType<DoubleVectorValue> {
                    override var nullable = false
                    override fun sqlType(): String = "vector($length)"
                    override fun valueFromDB(value: Any): DoubleVectorValue = DoubleVectorValue.parse(value.toString())
                    override fun notNullValueToDB(value: DoubleVectorValue): Any = PGvector(value.toString().replace("^^DoubleVector", ""))
                }
            )
            VectorValue.Type.Long -> registerColumn<LongVectorValue>("value",
                object : IColumnType<LongVectorValue> {
                    override var nullable = false
                    override fun sqlType(): String = "vector($length)"
                    override fun valueFromDB(value: Any): LongVectorValue = LongVectorValue.parse(value.toString())
                    override fun notNullValueToDB(value: LongVectorValue): Any = PGvector(value.toString().replace("^^LongVector", ""))
                }
            )
        }
        override val primaryKey = PrimaryKey(this.id)
    }
}
