package org.megras.graphstore.db

import com.google.common.cache.CacheBuilder
import com.pgvector.PGvector
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.megras.data.graph.*
import org.megras.graphstore.BasicQuadSet
import org.megras.graphstore.Distance
import org.megras.graphstore.MutableQuadSet
import org.megras.graphstore.QuadSet
import org.megras.util.TimingConfig
import org.slf4j.LoggerFactory
import java.io.Writer
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource


class PostgresStore(host: String = "localhost:5432/megras", user: String = "megras", password: String = "megras") :
    AbstractDbStore() {

    private val logger = LoggerFactory.getLogger(this.javaClass)
    private val TIMING_ENABLED get() = TimingConfig.enabled

    companion object {
        // Threshold for switching from OR-chain to VALUES clause approach
        // OR chains with more than this many conditions become very slow in PostgreSQL
        private const val OR_CHAIN_THRESHOLD = 100
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

        override val primaryKey = PrimaryKey(id)

        val sIndex = index(false, sType, s)
        val pIndex = index(false, p, pType)
        val oIndex = index(false, oType, o)

        val spIndex = index(false, sType, s, pType, p)
        val poIndex = index(false, pType, p, oType, o)

    }

    object StringLiteralTable : Table("literal_string") {
        val id: Column<Long> = long("id").autoIncrement().uniqueIndex()
        val value: Column<String> = text("value")

        override val primaryKey = PrimaryKey(id)
    }

    object DoubleLiteralTable : Table("literal_double") {
        val id: Column<Long> = long("id").autoIncrement().uniqueIndex()
        val value: Column<Double> = double("value").uniqueIndex()

        override val primaryKey = PrimaryKey(id)
    }

    object EntityPrefixTable : Table("entity_prefix") {
        val id: Column<Int> = integer("id").autoIncrement().uniqueIndex()
        val prefix: Column<String> = varchar("prefix", 255).uniqueIndex()

        override val primaryKey = PrimaryKey(id)
    }

    object EntityTable : Table("entity") {
        val id: Column<Long> = long("id").autoIncrement().uniqueIndex()
        val value: Column<String> = text("value").uniqueIndex()

        override val primaryKey = PrimaryKey(id)
    }

    object VectorTypesTable : Table("vector_types") {
        val id: Column<Int> = integer("id").autoIncrement().uniqueIndex()
        val type: Column<Int> = integer("type")
        val length: Column<Int> = integer("length")

        override val primaryKey = PrimaryKey(id)
    }

    private val db: Database

    init {
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:postgresql://$host"
            driverClassName = "org.postgresql.Driver"
            username = user
            this.password = password

            // Optimization settings for high-speed transactions
            maximumPoolSize = 10
            isAutoCommit = false // Exposed manages transactions, so disable auto-commit
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"

            // Value is in milliseconds (28 * 60 * 1000 = 1,680,000ms)
            maxLifetime = 1680000

            // Also recommend setting an idle timeout, so unused connections don't waste resources
            // Retire connections idle for more than 10 minutes (10 * 60 * 1000 = 600,000ms)
            idleTimeout = 600000
        }

        // 2. Create the Connection Pool
        val dataSource = HikariDataSource(config)

        // 3. Connect Exposed using the Pool
        db = Database.connect(dataSource)

        transaction {
            val schema = Schema("megras")
            SchemaUtils.createSchema(schema)
            SchemaUtils.setSchema(schema)
        }
    }

    override fun setup() {
        transaction {
            SchemaUtils.create(QuadsTable, StringLiteralTable, DoubleLiteralTable, EntityPrefixTable, EntityTable, VectorTypesTable)
            //TODO add this in a more idiomatic exposed way
            exec("ALTER TABLE megras.literal_string ADD COLUMN IF NOT EXISTS ts tsvector GENERATED ALWAYS AS (to_tsvector('english', value)) STORED;")
            exec("CREATE INDEX IF NOT EXISTS ts_idx ON megras.literal_string USING GIN (ts);")
            exec("CREATE EXTENSION IF NOT EXISTS vector;")
        }
        // Ensure HNSW indexes exist on all vector tables for fast KNN queries
        ensureVectorIndexes()
    }

    override fun lookUpDoubleValueIds(doubleValues: Set<DoubleValue>): Map<DoubleValue, QuadValueId> {
        if (doubleValues.isEmpty()) return emptyMap()
        val result = mutableMapOf<DoubleValue, QuadValueId>()
        doubleValues.map { it.value }.chunked(10000).forEach { chunk ->
            transaction {
                DoubleLiteralTable.select { DoubleLiteralTable.value inList chunk }.forEach {
                    result[DoubleValue(it[DoubleLiteralTable.value])] = (DOUBLE_LITERAL_TYPE to it[DoubleLiteralTable.id])
                }
            }
        }
        return result
    }

    override fun lookUpStringValueIds(stringValues: Set<StringValue>): Map<StringValue, QuadValueId> {
        if (stringValues.isEmpty()) return emptyMap()
        val result = mutableMapOf<StringValue, QuadValueId>()
        stringValues.map { it.value }.chunked(10000).forEach { chunk ->
            transaction {
                StringLiteralTable.select { StringLiteralTable.value inList chunk }.forEach {
                    result[StringValue(it[StringLiteralTable.value])] = (STRING_LITERAL_TYPE to it[StringLiteralTable.id])
                }
            }
        }
        return result
    }

    override fun lookUpPrefixIds(prefixValues: Set<String>): Map<String, Int> {
        if (prefixValues.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, Int>()
        prefixValues.chunked(10000).forEach { chunk ->
            transaction {
                EntityPrefixTable.select { EntityPrefixTable.prefix inList chunk }.forEach {
                    result[it[EntityPrefixTable.prefix]] = it[EntityPrefixTable.id]
                }
            }
        }
        return result
    }

    override fun lookUpSuffixIds(suffixValues: Set<String>): Map<String, Long> {
        if (suffixValues.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, Long>()
        suffixValues.chunked(10000).forEach { chunk ->
            transaction {
                EntityTable.select { EntityTable.value inList chunk }.forEach {
                    result[it[EntityTable.value]] = it[EntityTable.id]
                }
            }
        }
        return result
    }

    override fun lookUpVectorValueIds(vectorValues: Set<VectorValue>): Map<VectorValue, QuadValueId> {
            if (vectorValues.isEmpty()) {
                return emptyMap()
            }

            val returnMap = HashMap<VectorValue, QuadValueId>(vectorValues.size)

            vectorValues.groupBy { it.type to it.length }.forEach { (properties, vectorList) ->

                val vectorsInGroup = vectorList.toSet() // Set<VectorValue>
                val vectorTable = getOrCreateVectorTable(properties.first, properties.second)

                val queryResults = mutableListOf<Pair<Long, VectorValue>>()

                transaction {
                    when (properties.first) {
                        VectorValue.Type.Float -> {
                            @Suppress("UNCHECKED_CAST")
                            val specificValueColumn = vectorTable.value as Column<FloatVectorValue>
                            val floatVectors = vectorsInGroup.mapNotNull { it as? FloatVectorValue }
                            if (floatVectors.isNotEmpty()) {
                                floatVectors.chunked(10000).forEach { chunk ->
                                    queryResults.addAll(
                                        vectorTable.select { specificValueColumn inList chunk }
                                            .map { it[vectorTable.id] to it[specificValueColumn] }
                                    )
                                }
                            }
                        }
                        VectorValue.Type.Double -> {
                            @Suppress("UNCHECKED_CAST")
                            val specificValueColumn = vectorTable.value as Column<DoubleVectorValue>
                            val doubleVectors = vectorsInGroup.mapNotNull { it as? DoubleVectorValue }
                            if (doubleVectors.isNotEmpty()) {
                                doubleVectors.chunked(10000).forEach { chunk ->
                                    queryResults.addAll(
                                        vectorTable.select { specificValueColumn inList chunk }
                                            .map { it[vectorTable.id] to it[specificValueColumn] }
                                    )
                                }
                            }
                        }
                        VectorValue.Type.Long -> {
                            @Suppress("UNCHECKED_CAST")
                            val specificValueColumn = vectorTable.value as Column<LongVectorValue>
                            val longVectors = vectorsInGroup.mapNotNull { it as? LongVectorValue }
                            if (longVectors.isNotEmpty()) {
                                longVectors.chunked(10000).forEach { chunk ->
                                    queryResults.addAll(
                                        vectorTable.select { specificValueColumn inList chunk }
                                            .map { it[vectorTable.id] to it[specificValueColumn] }
                                    )
                                }
                            }
                        }
                    }
                }

                for ((id, value) in queryResults) {
                    val quadValueId = (-vectorTable.typeId + VECTOR_ID_OFFSET) to id
                    returnMap[value] = quadValueId
                }
            }

            return returnMap
        }

    override fun insertDoubleValues(doubleValues: Set<DoubleValue>): Map<DoubleValue, QuadValueId> {
        val list = doubleValues.toList()
        val results = transaction {
            DoubleLiteralTable.batchInsert(list) {
                this[DoubleLiteralTable.value] = it.value
            }.map { DOUBLE_LITERAL_TYPE to it[DoubleLiteralTable.id] }
        }
        return list.zip(results).toMap()
    }

    override fun insertStringValues(stringValues: Set<StringValue>): Map<StringValue, QuadValueId> {
        val list = stringValues.toList()
        val results = transaction {
            StringLiteralTable.batchInsert(list) {
                this[StringLiteralTable.value] = it.value
            }.map { STRING_LITERAL_TYPE to it[StringLiteralTable.id] }

        }
        return list.zip(results).toMap()
    }

    override fun insertPrefixValues(prefixValues: Set<String>): Map<String, Int> {
        val list = prefixValues.toList()
        val results = transaction {
            EntityPrefixTable.batchInsert(list) {
                this[EntityPrefixTable.prefix] = it
            }.map { it[EntityPrefixTable.id] }
        }
        return list.zip(results).toMap()
    }

    override fun insertSuffixValues(suffixValues: Set<String>): Map<String, Long> {
        val list = suffixValues.toList()
        val results = transaction {
            EntityTable.batchInsert(list) {
                this[EntityTable.value] = it
            }.map { it[EntityTable.id] }
        }
        return list.zip(results).toMap()
    }

override fun insertVectorValueIds(vectorValues: Set<VectorValue>): Map<VectorValue, QuadValueId> {
    vectorValues.groupBy { it.type to it.length }.forEach { (properties, v) ->
        val vectorsInGroup = v // v is List<VectorValue>
        val currentVectorType = properties.first
        val currentLength = properties.second
        // getOrCreateVectorTable returns a VectorTable instance where 'value' is Column<out VectorValue>
        // but its actual underlying type corresponds to currentVectorType
        val vectorTable = getOrCreateVectorTable(currentVectorType, currentLength)

        if (vectorsInGroup.isNotEmpty()) {
            transaction {
                vectorTable.batchInsert(vectorsInGroup) { it ->
                    when (currentVectorType) {
                        VectorValue.Type.Float -> {
                            @Suppress("UNCHECKED_CAST")
                            this[vectorTable.value as Column<FloatVectorValue>] = it as FloatVectorValue
                        }
                        VectorValue.Type.Double -> {
                            @Suppress("UNCHECKED_CAST")
                            this[vectorTable.value as Column<DoubleVectorValue>] = it as DoubleVectorValue
                        }
                        VectorValue.Type.Long -> {
                            @Suppress("UNCHECKED_CAST")
                            this[vectorTable.value as Column<LongVectorValue>] = it as LongVectorValue
                        }
                    }
                }
            }
        }
    }
    return lookUpVectorValueIds(vectorValues)
}

    class VectorTable(val typeId: Int, type: VectorValue.Type, length: Int) : Table("vector_values_$typeId") {
        val id: Column<Long> = long("id").autoIncrement().uniqueIndex()
        val value = when (type) {
            VectorValue.Type.Float -> registerColumn<FloatVectorValue>("value",
                object : ColumnType() {
                    override fun sqlType(): String = "vector($length)"
                    override fun valueFromDB(value: Any): FloatVectorValue {
                        return FloatVectorValue.parse(value.toString())
                    }

                    override fun notNullValueToDB(value: Any): Any {
                        return PGvector((value as FloatVectorValue).vector)
                    }
                }
            )
            VectorValue.Type.Double -> registerColumn<DoubleVectorValue>("value",
                object : ColumnType() {
                    override fun sqlType(): String = "vector($length)"
                    override fun valueFromDB(value: Any): DoubleVectorValue {
                        return DoubleVectorValue.parse(value.toString())
                    }

                    override fun notNullValueToDB(value: Any): Any {
                        return PGvector(value.toString().replace("^^DoubleVector", ""))
                    }
                }
            )
            VectorValue.Type.Long -> registerColumn<LongVectorValue>("value",
                object : ColumnType() {
                    override fun sqlType(): String = "vector($length)"
                    override fun valueFromDB(value: Any): LongVectorValue {
                        return LongVectorValue.parse(value.toString())
                    }

                    override fun notNullValueToDB(value: Any): Any {
                        return PGvector(value.toString().replace("^^LongVector", ""))
                    }
                }
            )
        }

        override val primaryKey = PrimaryKey(this.id)
    }

    private fun getOrCreateVectorTable(type: VectorValue.Type, length: Int): VectorTable {
        fun createTable(): VectorTable {
            val result = transaction {
                VectorTypesTable.insert {
                    it[VectorTypesTable.type] = type.ordinal
                    it[VectorTypesTable.length] = length
                }
            }
            val id = result[VectorTypesTable.id]
            val vectorTable = VectorTable(id, type, length)
            transaction {
                SchemaUtils.create(vectorTable)
                // Create HNSW index for fast cosine distance KNN queries
                // This dramatically improves nearestNeighbor performance from O(n) to O(log n)
                exec("CREATE INDEX IF NOT EXISTS vector_values_${id}_hnsw_idx ON megras.vector_values_${id} USING hnsw (value vector_cosine_ops) WITH (m = 16, ef_construction = 64)")
            }

            return vectorTable
        }

        return getVectorTable(type, length) ?: createTable()
    }

    /**
     * Ensures HNSW indexes exist on all vector tables for fast KNN queries.
     * Call this once during setup or manually to add indexes to existing tables.
     */
    fun ensureVectorIndexes() {
        val startTime = System.currentTimeMillis()

        transaction {
            val vectorTypes = VectorTypesTable.selectAll().map {
                Triple(it[VectorTypesTable.id], it[VectorTypesTable.type], it[VectorTypesTable.length])
            }

            for ((id, _, _) in vectorTypes) {
                try {
                    exec("CREATE INDEX IF NOT EXISTS vector_values_${id}_hnsw_idx ON megras.vector_values_${id} USING hnsw (value vector_cosine_ops) WITH (m = 16, ef_construction = 64)")
                } catch (e: Exception) {
                    logger.warn("Failed to create HNSW index for vector_values_$id: ${e.message}")
                }
            }
        }

        if (TIMING_ENABLED) logger.info("Vector index check completed in ${System.currentTimeMillis() - startTime}ms")
    }

    private fun getVectorTable(type: VectorValue.Type, length: Int): VectorTable? {
        val tableId = transaction {
            VectorTypesTable.select {
                (VectorTypesTable.type eq type.ordinal) and (VectorTypesTable.length eq length)
            }.map { it[VectorTypesTable.id] }.firstOrNull()
        }

        return if (tableId != null) {
            VectorTable(tableId, type, length)
        } else {
            null
        }
    }

    override fun lookUpDoubleValues(ids: Set<Long>): Map<QuadValueId, DoubleValue> {
        if (ids.isEmpty()) return emptyMap()
        val result = mutableMapOf<QuadValueId, DoubleValue>()
        ids.chunked(10000).forEach { chunk ->
            transaction {
                DoubleLiteralTable.select { DoubleLiteralTable.id inList chunk }.forEach {
                    result[(DOUBLE_LITERAL_TYPE to it[DoubleLiteralTable.id])] = DoubleValue(it[DoubleLiteralTable.value])
                }
            }
        }
        return result
    }

    override fun lookUpStringValues(ids: Set<Long>): Map<QuadValueId, StringValue> {
        if (ids.isEmpty()) return emptyMap()
        val result = mutableMapOf<QuadValueId, StringValue>()
        ids.chunked(10000).forEach { chunk ->
            transaction {
                StringLiteralTable.select { StringLiteralTable.id inList chunk }.forEach {
                    result[(STRING_LITERAL_TYPE to it[StringLiteralTable.id])] = StringValue(it[StringLiteralTable.value])
                }
            }
        }
        return result
    }

    override fun lookUpVectorValues(ids: Set<QuadValueId>): Map<QuadValueId, VectorValue> {
        if (ids.isEmpty()) {
            return emptyMap()
        }

        val returnMap = HashMap<QuadValueId, VectorValue>(ids.size)

        ids.groupBy { it.first }.forEach { (type, quadValueIds) ->
            val longIds = quadValueIds.map { it.second }
            longIds.chunked(10000).forEach { chunk ->
                val values = getVectorQuadValues(type, chunk)
                values.forEach { (longId, vectorValue) ->
                    returnMap[type to longId] = vectorValue
                }
            }
        }
        //TODO batch by type
        return returnMap
    }

    private fun getVectorQuadValues(type: Int, ids: List<Long>): Map<Long, VectorValue> {
        if (ids.isEmpty()) return emptyMap()

        val internalId = -type + VECTOR_ID_OFFSET

        val properties = getVectorProperties(internalId) ?: return emptyMap()

        val vectorTable = getOrCreateVectorTable(properties.second, properties.first)

        val result = mutableMapOf<Long, Any>()
        ids.chunked(10000).forEach { chunk ->
            transaction {
                vectorTable.select {
                    vectorTable.id inList chunk
                }.forEach {
                    result[it[vectorTable.id]] = it[vectorTable.value]
                }
            }
        }

        val map = mutableMapOf<Long, VectorValue>()

        when(properties.second) {
            VectorValue.Type.Double -> {
                result.forEach { (id, value) ->
                    map[id] = value as DoubleVectorValue
                }
            }
            VectorValue.Type.Long -> {
                result.forEach { (id, value) ->
                    map[id] = value as LongVectorValue
                }
            }
            VectorValue.Type.Float -> {
                result.forEach { (id, value) ->
                    map[id] = value as FloatVectorValue
                }
            }
        }

        return map
    }

    private fun getVectorProperties(type: Int): Pair<Int, VectorValue.Type>? {
        return transaction {
            VectorTypesTable
                .select { VectorTypesTable.id eq type }
                .firstOrNull()
                ?.let { row ->
                    row[VectorTypesTable.length] to VectorValue.Type.values()[row[VectorTypesTable.type]]
                }
        }
    }

    override fun lookUpPrefixes(ids: Set<Int>): Map<Int, String> {
        if (ids.isEmpty()) return emptyMap()
        val result = mutableMapOf<Int, String>()
        ids.chunked(10000).forEach { chunk ->
            transaction {
                EntityPrefixTable.select { EntityPrefixTable.id inList chunk }.forEach {
                    result[it[EntityPrefixTable.id]] = it[EntityPrefixTable.prefix]
                }
            }
        }
        return result
    }

    override fun lookUpSuffixes(ids: Set<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        val result = mutableMapOf<Long, String>()
        ids.chunked(10000).forEach { chunk ->
            transaction {
                EntityTable.select { EntityTable.id inList chunk }.forEach {
                    result[it[EntityTable.id]] = it[EntityTable.value]
                }
            }
        }
        return result
    }

    override fun insert(s: QuadValueId, p: QuadValueId, o: QuadValueId): Long {
        return transaction {
            QuadsTable.insert {
                it[sType] = s.first
                it[this.s] = s.second
                it[pType] = p.first
                it[this.p] = p.second
                it[oType] = o.first
                it[this.o] = o.second
                it[hash] = quadHash(s.first, s.second, p.first, p.second, o.first, o.second)

            }[QuadsTable.id]
        }
    }

    override fun getQuadId(s: QuadValueId, p: QuadValueId, o: QuadValueId): Long? {
        return transaction {
            QuadsTable.slice(QuadsTable.id).select {
                QuadsTable.hash eq quadHash(s.first, s.second, p.first, p.second, o.first, o.second)
            }.firstOrNull()?.get(QuadsTable.id)
        }
    }

    override fun getId(id: Long): Quad? {
        val quadIds = transaction {
            QuadsTable.select { QuadsTable.id eq id }.firstOrNull()?.let {
                listOf(
                    it[QuadsTable.sType] to it[QuadsTable.s],
                    it[QuadsTable.pType] to it[QuadsTable.p],
                    it[QuadsTable.oType] to it[QuadsTable.o]
                )
            }
        } ?: return null

        val values = getQuadValues(quadIds)

        val s = values[quadIds[0]] ?: return null
        val p = values[quadIds[1]] ?: return null
        val o = values[quadIds[2]] ?: return null

        return Quad(id, s, p, o)
    }

    private val idCache = CacheBuilder.newBuilder().maximumSize(cacheSize).build<Long, Triple<QuadValueId, QuadValueId, QuadValueId>>()

    private fun getIds(ids: Collection<Long>): QuadSet {
        if (ids.isEmpty()) {
            return BasicQuadSet()
        }

        val mutableIds = ids.toMutableSet()

        val quadIds = mutableSetOf<Pair<Long, Triple<QuadValueId, QuadValueId, QuadValueId>>>()

        mutableIds.removeIf {
            val cached = idCache.getIfPresent(it)
            if (cached != null) {
                quadIds.add(it to cached)
                true
            } else {
                false
            }
        }

        if (mutableIds.isNotEmpty()) {
            //TODO: optimize to custom SQL
            mutableIds.chunked(10000).forEach { chunk ->
                transaction {
                    val lookUpQuadIds = QuadsTable.slice(QuadsTable.id, QuadsTable.sType, QuadsTable.s, QuadsTable.pType, QuadsTable.p, QuadsTable.oType, QuadsTable.o)
                        .select { QuadsTable.id inList chunk }.map {
                            it[QuadsTable.id] to Triple(
                                (it[QuadsTable.sType] to it[QuadsTable.s]),
                                (it[QuadsTable.pType] to it[QuadsTable.p]),
                                (it[QuadsTable.oType] to it[QuadsTable.o]),
                            )
                        }

                    lookUpQuadIds.forEach {
                        idCache.put(it.first, it.second)
                    }

                    quadIds.addAll(lookUpQuadIds)
                }
            }
        }

        return getIds(quadIds)
    }

    fun getIds(quadIds: Set<Pair<Long, Triple<QuadValueId, QuadValueId, QuadValueId>>>): QuadSet {
        val quadValueIds = quadIds.flatMap { listOf(it.second.first, it.second.second, it.second.third) }.toSet()
        val quadValues = getQuadValues(quadValueIds)

        return BasicQuadSet(
            quadIds.mapNotNull {
                val s = quadValues[it.second.first]
                val p = quadValues[it.second.second]
                val o = quadValues[it.second.third]

                if (s != null && p != null && o != null) {
                    Quad(it.first, s, p, o)
                } else {
                    null
                }
            }.toSet()
        )
    }

    override fun filterSubject(subject: QuadValue): QuadSet {
        val id = getQuadValueId(subject)

        if (id.first == null || id.second == null) {
            return BasicQuadSet()
        }

        val quadIds = transaction {
            QuadsTable.slice(QuadsTable.id).select { (QuadsTable.sType eq id.first!!) and (QuadsTable.s eq id.second!!) }
                .map { it[QuadsTable.id] }
        }

        return getIds(quadIds)
    }

    override fun filterPredicate(predicate: QuadValue): QuadSet {
        val id = getQuadValueId(predicate)

        if (id.first == null || id.second == null) {
            return BasicQuadSet()
        }

        val quadIds = transaction {
            QuadsTable.slice(QuadsTable.id).select { (QuadsTable.pType eq id.first!!) and (QuadsTable.p eq id.second!!) }
                .map { it[QuadsTable.id] }
        }

        return getIds(quadIds)
    }

    override fun filterObject(`object`: QuadValue): QuadSet {
        val id = getQuadValueId(`object`)

        if (id.first == null || id.second == null) {
            return BasicQuadSet()
        }

        val quadIds = transaction {
            QuadsTable.slice(QuadsTable.id).select { (QuadsTable.oType eq id.first!!) and (QuadsTable.o eq id.second!!) }
                .map { it[QuadsTable.id] }
        }

        return getIds(quadIds)
    }

    private fun getIdsForFilter(ids: List<Pair<Int, Long>>?, part: Char): Set<Long>? {
        if (ids == null) return null
        if (ids.isEmpty()) return emptySet()

        val (typeColumn, idColumn) = when (part) {
            's' -> QuadsTable.sType to QuadsTable.s
            'p' -> QuadsTable.pType to QuadsTable.p
            'o' -> QuadsTable.oType to QuadsTable.o
            else -> throw IllegalArgumentException("part must be 's', 'p', or 'o'")
        }

        val quadIds = mutableSetOf<Long>()
        // Each pair in the chunk results in two parameters
        ids.chunked(10000).forEach { chunk ->
            transaction {
                val filter = chunk.map { (typeColumn eq it.first) and (idColumn eq it.second) }.reduce { acc, op -> acc or op }
                QuadsTable.slice(QuadsTable.id).select(filter).mapTo(quadIds) { it[QuadsTable.id] }
            }
        }
        return quadIds
    }

    override fun filter(
        subjects: Collection<QuadValue>?,
        predicates: Collection<QuadValue>?,
        objects: Collection<QuadValue>?
    ): QuadSet {

        val startTotal = if (TIMING_ENABLED) System.currentTimeMillis() else 0L

        // 1. Handle Trivial Cases (empty or no filters)
        val start1 = if (TIMING_ENABLED) System.currentTimeMillis() else 0L
        if (subjects == null && predicates == null && objects == null) return this
        if (subjects?.isEmpty() == true || predicates?.isEmpty() == true || objects?.isEmpty() == true) return BasicQuadSet()
        if (TIMING_ENABLED) logger.info("Time spent in Filter Trivial Checks: ${System.currentTimeMillis() - start1}ms")


        // 2. Resolve QuadValues to (typeId, longId) pairs
        val start2 = if (TIMING_ENABLED) System.currentTimeMillis() else 0L
        val allFilterValues = (subjects?.toSet() ?: emptySet()) + (predicates?.toSet() ?: emptySet()) + (objects?.toSet() ?: emptySet())
        val filterIds = getOrAddQuadValueIds(allFilterValues, false)

        val subjectFilterIds = subjects?.mapNotNull { filterIds[it] }
        val predicateFilterIds = predicates?.mapNotNull { filterIds[it] }
        val objectFilterIds = objects?.mapNotNull { filterIds[it] }

        if ((subjects != null && subjectFilterIds!!.isEmpty()) ||
            (predicates != null && predicateFilterIds!!.isEmpty()) ||
            (objects != null && objectFilterIds!!.isEmpty())) {
            if (TIMING_ENABLED) logger.info("Time spent in Filter ID Resolution (Early Exit): ${System.currentTimeMillis() - start2}ms")
            return BasicQuadSet()
        }
        if (TIMING_ENABLED) logger.info("Time spent in Filter ID Resolution (getOrAddQuadValueIds): ${System.currentTimeMillis() - start2}ms")

        // Check if any filter set is large enough to warrant VALUES clause approach
        val useLargeSubjectFilter = subjectFilterIds != null && subjectFilterIds.size > OR_CHAIN_THRESHOLD
        val useLargePredicateFilter = predicateFilterIds != null && predicateFilterIds.size > OR_CHAIN_THRESHOLD
        val useLargeObjectFilter = objectFilterIds != null && objectFilterIds.size > OR_CHAIN_THRESHOLD

        val finalSeptuples = mutableSetOf<Pair<Long, Triple<QuadValueId, QuadValueId, QuadValueId>>>()

        // 3. Execute Database Query - use optimized approach for large filter sets
        val start3 = if (TIMING_ENABLED) System.currentTimeMillis() else 0L

        if (useLargeSubjectFilter || useLargePredicateFilter || useLargeObjectFilter) {
            // Use VALUES clause approach for large filter sets
            if (TIMING_ENABLED) {
                logger.info("Using VALUES clause approach - subjects: ${subjectFilterIds?.size ?: "null"}, predicates: ${predicateFilterIds?.size ?: "null"}, objects: ${objectFilterIds?.size ?: "null"}")
            }
            executeFilterWithValuesClause(
                subjectFilterIds, predicateFilterIds, objectFilterIds,
                useLargeSubjectFilter, useLargePredicateFilter, useLargeObjectFilter,
                finalSeptuples
            )
        } else {
            // Use traditional OR-chain approach for small filter sets
            executeFilterWithOrChain(subjectFilterIds, predicateFilterIds, objectFilterIds, finalSeptuples)
        }

        if (TIMING_ENABLED) logger.info("Time spent in DB Transaction (Main Query Execution): ${System.currentTimeMillis() - start3}ms")

        // 4. Return the resulting QuadSet
        val start4 = if (TIMING_ENABLED) System.currentTimeMillis() else 0L
        val quadSet = getIds(finalSeptuples) // Converting IDs back to QuadValues
        if (TIMING_ENABLED) logger.info("Time spent in Final ID Conversion (getIds): ${System.currentTimeMillis() - start4}ms")

        if (TIMING_ENABLED) logger.info("Total time spent in PostgresStore.filter: ${System.currentTimeMillis() - startTotal}ms")
        return quadSet
    }

    /**
     * Execute filter using traditional OR-chain approach (efficient for small filter sets)
     */
    private fun executeFilterWithOrChain(
        subjectFilterIds: List<Pair<Int, Long>>?,
        predicateFilterIds: List<Pair<Int, Long>>?,
        objectFilterIds: List<Pair<Int, Long>>?,
        finalSeptuples: MutableSet<Pair<Long, Triple<QuadValueId, QuadValueId, QuadValueId>>>
    ) {
        transaction {
            var condition: Op<Boolean> = Op.TRUE

            if (subjectFilterIds != null) {
                val sCondition = subjectFilterIds
                    .map { (QuadsTable.sType eq it.first) and (QuadsTable.s eq it.second) }
                    .reduce { acc, o -> acc or o }
                condition = condition and sCondition
            }
            if (predicateFilterIds != null) {
                val pCondition = predicateFilterIds
                    .map { (QuadsTable.pType eq it.first) and (QuadsTable.p eq it.second) }
                    .reduce { acc, o -> acc or o }
                condition = condition and pCondition
            }
            if (objectFilterIds != null) {
                val oCondition = objectFilterIds
                    .map { (QuadsTable.oType eq it.first) and (QuadsTable.o eq it.second) }
                    .reduce { acc, o -> acc or o }
                condition = condition and oCondition
            }

            val result = QuadsTable.slice(
                QuadsTable.id, QuadsTable.sType, QuadsTable.s, QuadsTable.pType, QuadsTable.p, QuadsTable.oType, QuadsTable.o
            ).select(condition)
                .map {
                    it[QuadsTable.id] to Triple(
                        (it[QuadsTable.sType] to it[QuadsTable.s]),
                        (it[QuadsTable.pType] to it[QuadsTable.p]),
                        (it[QuadsTable.oType] to it[QuadsTable.o]),
                    )
                }

            finalSeptuples.addAll(result)
        }
    }

    /**
     * Execute filter using VALUES clause approach (efficient for large filter sets)
     * Uses raw SQL with VALUES clause which PostgreSQL optimizes much better than large OR chains
     */
    private fun executeFilterWithValuesClause(
        subjectFilterIds: List<Pair<Int, Long>>?,
        predicateFilterIds: List<Pair<Int, Long>>?,
        objectFilterIds: List<Pair<Int, Long>>?,
        useLargeSubjectFilter: Boolean,
        useLargePredicateFilter: Boolean,
        useLargeObjectFilter: Boolean,
        finalSeptuples: MutableSet<Pair<Long, Triple<QuadValueId, QuadValueId, QuadValueId>>>
    ) {
        transaction {
            // Build the SQL query with VALUES clauses for large filters
            val sqlBuilder = StringBuilder()
            sqlBuilder.append("SELECT q.id, q.s_type, q.s, q.p_type, q.p, q.o_type, q.o FROM quads q")

            val joins = mutableListOf<String>()
            val conditions = mutableListOf<String>()

            // Handle subject filter
            if (subjectFilterIds != null) {
                if (useLargeSubjectFilter) {
                    val valuesClause = subjectFilterIds.joinToString(",") { "(${it.first},${it.second})" }
                    joins.add(" INNER JOIN (VALUES $valuesClause) AS sf(s_type, s_id) ON q.s_type = sf.s_type AND q.s = sf.s_id")
                } else {
                    val orConditions = subjectFilterIds.joinToString(" OR ") { "(q.s_type = ${it.first} AND q.s = ${it.second})" }
                    conditions.add("($orConditions)")
                }
            }

            // Handle predicate filter
            if (predicateFilterIds != null) {
                if (useLargePredicateFilter) {
                    val valuesClause = predicateFilterIds.joinToString(",") { "(${it.first},${it.second})" }
                    joins.add(" INNER JOIN (VALUES $valuesClause) AS pf(p_type, p_id) ON q.p_type = pf.p_type AND q.p = pf.p_id")
                } else {
                    val orConditions = predicateFilterIds.joinToString(" OR ") { "(q.p_type = ${it.first} AND q.p = ${it.second})" }
                    conditions.add("($orConditions)")
                }
            }

            // Handle object filter
            if (objectFilterIds != null) {
                if (useLargeObjectFilter) {
                    val valuesClause = objectFilterIds.joinToString(",") { "(${it.first},${it.second})" }
                    joins.add(" INNER JOIN (VALUES $valuesClause) AS of(o_type, o_id) ON q.o_type = of.o_type AND q.o = of.o_id")
                } else {
                    val orConditions = objectFilterIds.joinToString(" OR ") { "(q.o_type = ${it.first} AND q.o = ${it.second})" }
                    conditions.add("($orConditions)")
                }
            }

            // Build final query
            for (join in joins) {
                sqlBuilder.append(join)
            }

            if (conditions.isNotEmpty()) {
                sqlBuilder.append(" WHERE ")
                sqlBuilder.append(conditions.joinToString(" AND "))
            }

            val sql = sqlBuilder.toString()

            // Execute raw SQL
            exec(sql) { rs ->
                while (rs.next()) {
                    val id = rs.getLong("id")
                    val sType = rs.getInt("s_type")
                    val s = rs.getLong("s")
                    val pType = rs.getInt("p_type")
                    val p = rs.getLong("p")
                    val oType = rs.getInt("o_type")
                    val o = rs.getLong("o")

                    finalSeptuples.add(
                        id to Triple(
                            (sType to s),
                            (pType to p),
                            (oType to o)
                        )
                    )
                }
            }
        }
    }

    override fun toMutable(): MutableQuadSet = this

    override fun toSet(): Set<Quad> {
        TODO("Not yet implemented")
    }

    override fun plus(other: QuadSet): QuadSet {
        TODO("Not yet implemented")
    }

    private class VectorDistance(
        private val column: Expression<*>,
        private val target: VectorValue,
        private val distance: Distance
    ) : Op<Float>() {
        override fun toQueryBuilder(queryBuilder: QueryBuilder) {
            val op = when (distance) {
                Distance.COSINE -> "<=>"
                Distance.DOTPRODUCT -> "<#>"
            }

            // The PGvector class from the library handles the string representation.
            val pgVector = when(target) {
                is FloatVectorValue -> PGvector(target.vector)
                // pgvector works with float vectors.
                is DoubleVectorValue -> PGvector(target.vector.map { it.toFloat() }.toFloatArray())
                is LongVectorValue -> PGvector(target.vector.map { it.toFloat() }.toFloatArray())
                else -> {TODO("Unsupported vector type: ${target.type}") }
            }

            queryBuilder.append("(")
            column.toQueryBuilder(queryBuilder)
            queryBuilder.append(" $op ")
            queryBuilder.append(stringLiteral(pgVector.toString()))
            queryBuilder.append(")")
        }
    }

    override fun nearestNeighbor(predicate: QuadValue, `object`: VectorValue, count: Int, distance: Distance, invert: Boolean): QuadSet {
        val startTime = if (TIMING_ENABLED) System.currentTimeMillis() else 0L

        val predicateId = getQuadValueId(predicate)
        if (predicateId.first == null || predicateId.second == null) {
            if (TIMING_ENABLED) logger.info("PostgresStore.nearestNeighbor: Unknown predicate, returning empty in ${System.currentTimeMillis() - startTime}ms")
            return BasicQuadSet()
        }

        // We are querying, so we only care about existing tables.
        val vectorTable = getVectorTable(`object`.type, `object`.length)
        if (vectorTable == null) {
            if (TIMING_ENABLED) logger.info("PostgresStore.nearestNeighbor: No vector table found for type=${`object`.type}, length=${`object`.length}, returning empty in ${System.currentTimeMillis() - startTime}ms")
            return BasicQuadSet()
        }

        val distanceExpression = VectorDistance(vectorTable.value, `object`, distance)

        val queryStartTime = if (TIMING_ENABLED) System.currentTimeMillis() else 0L
        val quadIds = transaction {
            QuadsTable.join(
                vectorTable,
                JoinType.INNER,
                onColumn = QuadsTable.o,
                otherColumn = vectorTable.id
            ) {
                (QuadsTable.oType eq (-vectorTable.typeId + VECTOR_ID_OFFSET))
            }
            .slice(QuadsTable.id)
            .select {
                (QuadsTable.pType eq predicateId.first!!) and (QuadsTable.p eq predicateId.second!!)
            }
            .orderBy(distanceExpression to if (invert) SortOrder.DESC else SortOrder.ASC)
            .limit(count)
            .map { it[QuadsTable.id] }
        }
        if (TIMING_ENABLED) logger.info("PostgresStore.nearestNeighbor: DB query returned ${quadIds.size} quad IDs in ${System.currentTimeMillis() - queryStartTime}ms")

        val result = getIds(quadIds)
        if (TIMING_ENABLED) logger.info("PostgresStore.nearestNeighbor: Total time ${System.currentTimeMillis() - startTime}ms, returning ${result.size} quads")
        return result
    }


    private class FullTextSearch(
        private val q: String,
    ) : Op<Boolean>() {
        override fun toQueryBuilder(queryBuilder: QueryBuilder): Unit = queryBuilder.run {
            append("ts @@ phraseto_tsquery('english', ")
            append(stringLiteral(q))
            append(")")
        }
    }

    override fun textFilter(predicate: QuadValue, objectFilterText: String): QuadSet {
        val predicatePair = getQuadValueId(predicate)

        if (predicatePair.first == null || predicatePair.second == null) { //unknown predicate, can't have matching quads
            return BasicQuadSet()
        }

       val textIds = transaction {
           StringLiteralTable.slice(StringLiteralTable.id).select(FullTextSearch(objectFilterText)).map { it[StringLiteralTable.id] }
       }

        val quadIds = mutableListOf<Long>()
        textIds.chunked(10000).forEach { chunk ->
            transaction {
                quadIds.addAll(
                    QuadsTable.slice(QuadsTable.id).select { (QuadsTable.pType eq predicatePair.first!!) and (QuadsTable.p eq predicatePair.second!!) and (QuadsTable.oType eq STRING_LITERAL_TYPE) and (QuadsTable.o inList chunk) }
                        .map { it[QuadsTable.id] }
                )
            }
        }

        return getIds(quadIds)
    }

    /**
     * Optimized implementation that uses SQL DISTINCT to get unique object values.
     * This avoids fetching all rows and deduplicating in memory.
     */
    override fun distinctObjects(predicate: QuadValue): Set<QuadValue> {
        val startTime = if (TIMING_ENABLED) System.currentTimeMillis() else 0L

        val predicateId = getQuadValueId(predicate)
        if (predicateId.first == null || predicateId.second == null) {
            return emptySet()
        }

        // Query distinct (oType, o) pairs from the database
        val distinctObjectIds = transaction {
            QuadsTable
                .slice(QuadsTable.oType, QuadsTable.o)
                .select { (QuadsTable.pType eq predicateId.first!!) and (QuadsTable.p eq predicateId.second!!) }
                .withDistinct()
                .map { it[QuadsTable.oType] to it[QuadsTable.o] }
                .toSet()
        }

        if (TIMING_ENABLED) {
            logger.info("distinctObjects: Found ${distinctObjectIds.size} distinct objects in ${System.currentTimeMillis() - startTime}ms")
        }

        // Convert IDs back to QuadValues
        val quadValues = getQuadValues(distinctObjectIds)

        if (TIMING_ENABLED) {
            logger.info("distinctObjects: Total time ${System.currentTimeMillis() - startTime}ms")
        }

        return quadValues.values.toSet()
    }

    /**
     * Optimized implementation that uses SQL DISTINCT to get unique subject values.
     * This avoids fetching all rows and deduplicating in memory.
     */
    override fun distinctSubjects(predicate: QuadValue): Set<QuadValue> {
        val startTime = if (TIMING_ENABLED) System.currentTimeMillis() else 0L

        val predicateId = getQuadValueId(predicate)
        if (predicateId.first == null || predicateId.second == null) {
            return emptySet()
        }

        // Query distinct (sType, s) pairs from the database
        val distinctSubjectIds = transaction {
            QuadsTable
                .slice(QuadsTable.sType, QuadsTable.s)
                .select { (QuadsTable.pType eq predicateId.first!!) and (QuadsTable.p eq predicateId.second!!) }
                .withDistinct()
                .map { it[QuadsTable.sType] to it[QuadsTable.s] }
                .toSet()
        }

        if (TIMING_ENABLED) {
            logger.info("distinctSubjects: Found ${distinctSubjectIds.size} distinct subjects in ${System.currentTimeMillis() - startTime}ms")
        }

        // Convert IDs back to QuadValues
        val quadValues = getQuadValues(distinctSubjectIds)

        if (TIMING_ENABLED) {
            logger.info("distinctSubjects: Total time ${System.currentTimeMillis() - startTime}ms")
        }

        return quadValues.values.toSet()
    }

    override val size: Int
        get() {
            return transaction {
                QuadsTable.selectAll().count().toInt()
            }
        }

    override fun isEmpty(): Boolean = this.size > 0

    override fun iterator(): MutableIterator<Quad> {
        //FIXME this is not efficient, but improving is a a lot of work
        val allIds = transaction {
            QuadsTable.slice(QuadsTable.id).selectAll().map { it[QuadsTable.id] }
        }
        val quadSet = getIds(allIds)
        return quadSet.toMutableSet().iterator()
    }

    override fun addAll(elements: Collection<Quad>): Boolean {
            if (elements.isEmpty()) {
                return true
            }

            val values = elements.flatMap {
                sequenceOf(
                    it.subject, it.predicate, it.`object`
                )
            }.toSet()

            val valueIdMap = getOrAddQuadValueIds(values)

            val quadIdMap = elements.mapNotNull {
                val s = valueIdMap[it.subject]
                val p = valueIdMap[it.predicate]
                val o = valueIdMap[it.`object`]

                if (s == null || p == null || o == null) {
                    System.err.println("${it.subject}: $s, ${it.predicate}: $p, ${it.`object`}: $o")
                    return@mapNotNull null
                }

                quadHash(s.first, s.second, p.first, p.second, o.first, o.second) to it
            }.toMap().toMutableMap()

            quadIdMap.keys.chunked(10000).forEach { chunk ->
                transaction {
                    QuadsTable.slice(QuadsTable.hash).select { QuadsTable.hash inList chunk }.forEach {
                        quadIdMap.remove(it[QuadsTable.hash])
                    }
                }
            }

            if (quadIdMap.isEmpty()) {
                return false
            }

            transaction {
                QuadsTable.batchInsert(quadIdMap.values) {
                    val s = valueIdMap[it.subject]!!
                    val p = valueIdMap[it.predicate]!!
                    val o = valueIdMap[it.`object`]!!
                    this[QuadsTable.sType] = s.first
                    this[QuadsTable.s] = s.second
                    this[QuadsTable.pType] = p.first
                    this[QuadsTable.p] = p.second
                    this[QuadsTable.oType] = o.first
                    this[QuadsTable.o] = o.second
                    this[QuadsTable.hash] = quadHash(s.first, s.second, p.first, p.second, o.first, o.second)
                }
            }

            return true
        }

    override fun clear() {
        TODO("Not yet implemented")
    }

    override fun remove(element: Quad): Boolean {
        TODO("Not yet implemented")
    }

    override fun removeAll(elements: Collection<Quad>): Boolean {
        TODO("Not yet implemented")
    }

    override fun retainAll(elements: Collection<Quad>): Boolean {
        TODO("Not yet implemented")
    }

    override fun dump(writer: Writer, chunkSize: Int) {
        // Write header
        writer.write("subject\tpredicate\tobject\n")

        val allIds = transaction { QuadsTable.selectAll().map { it[QuadsTable.id] } }
        val totalQuads = allIds.size.toLong()
        var quadsProcessed = 0L

        if (totalQuads == 0L) {
            logger.info("No quads to dump.")
            return
        }

        allIds.chunked(chunkSize).forEach { chunk ->
            val quads = getIds(chunk).toSet()
            for (quad in quads) {
                val formattedSubject = quad.subject.toString()
                // Skip localhost subjects if they are not LocalQuadValue
                if (formattedSubject.contains("localhost") && quad.subject !is LocalQuadValue) {
                    continue
                }
                val formattedPredicate = quad.predicate.toString()
                val formattedObject = formatQuadValueForTsv(quad.`object`)
                writer.write("$formattedSubject\t$formattedPredicate\t$formattedObject\n")
            }
            writer.flush()
            quadsProcessed += chunk.size
            val percentage = (quadsProcessed * 100) / totalQuads
            logger.info("Progress: $percentage% ($quadsProcessed / $totalQuads)")
        }
    }

    /**
     * Extracts the string representation of a QuadValue, including its type suffix,
     * and handles any necessary internal escaping for TSV (but no outer quoting if not needed).
     *
     * @param quadValue The QuadValue object.
     * @return The TSV-formatted string for the object column, including suffix and proper internal escaping.
     */
    fun formatQuadValueForTsv(quadValue: QuadValue): String {
        // Get the string representation of the QuadValue, including the desired suffix.
        val stringRepresentationIncludingSuffix = quadValue.toString()

        // Standard TSV rules still apply for delimiter (tab), newline, and quote character.
        val needsInternalEscapingAndPossiblyOuterQuoting =
            stringRepresentationIncludingSuffix.contains('\t') ||
                    stringRepresentationIncludingSuffix.contains('\n') ||
                    stringRepresentationIncludingSuffix.contains('"')

        if (needsInternalEscapingAndPossiblyOuterQuoting) {
            // Escape internal double quotes by doubling them
            val escapedValue = stringRepresentationIncludingSuffix.replace("\"", "\"\"")
            return "\"$escapedValue\""
        } else {
            // No special characters requiring quoting or escaping, return as is
            return stringRepresentationIncludingSuffix
        }
    }
}