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


class PostgresStore(host: String = "localhost:5432/megras", user: String = "megras", password: String = "megras") :
    AbstractDbStore() {

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
        db = Database.connect(
            "jdbc:postgresql://$host",
            driver = "org.postgresql.Driver",
            user = user, password = password
        )

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
    }

    override fun lookUpDoubleValueIds(doubleValues: Set<DoubleValue>): Map<DoubleValue, QuadValueId> {
        return transaction {
            DoubleLiteralTable.select { DoubleLiteralTable.value inList doubleValues.map { it.value } }.associate {
                DoubleValue(it[DoubleLiteralTable.value]) to (DOUBLE_LITERAL_TYPE to it[DoubleLiteralTable.id])
            }
        }
    }

    override fun lookUpStringValueIds(stringValues: Set<StringValue>): Map<StringValue, QuadValueId> {
        return transaction {
            StringLiteralTable.select { StringLiteralTable.value inList stringValues.map { it.value } }.associate {
                StringValue(it[StringLiteralTable.value]) to (STRING_LITERAL_TYPE to it[StringLiteralTable.id])
            }
        }
    }

    override fun lookUpPrefixIds(prefixValues: Set<String>): Map<String, Int> {
        return transaction {
            EntityPrefixTable.select { EntityPrefixTable.prefix inList prefixValues }.associate {
                it[EntityPrefixTable.prefix] to it[EntityPrefixTable.id]
            }
        }
    }

    override fun lookUpSuffixIds(suffixValues: Set<String>): Map<String, Long> {
        return transaction {
            EntityTable.select { EntityTable.value inList suffixValues }.associate {
                it[EntityTable.value] to it[EntityTable.id]
            }
        }
    }

    override fun lookUpVectorValueIds(vectorValues: Set<VectorValue>): Map<VectorValue, QuadValueId> {
        if (vectorValues.isEmpty()) {
            return emptyMap()
        }

        val returnMap = HashMap<VectorValue, QuadValueId>(vectorValues.size)

        vectorValues.groupBy { it.type to it.length }.forEach { (properties, vectorList) ->

            val vectorsInGroup = vectorList.toSet() // Set<VectorValue>
            val vectorTable = getOrCreateVectorTable(properties.first, properties.second)

            val queryResults: List<Pair<Long, VectorValue>> = transaction {
                when (properties.first) {
                    VectorValue.Type.Float -> {
                        @Suppress("UNCHECKED_CAST")
                        val specificValueColumn = vectorTable.value as Column<FloatVectorValue>
                        val floatVectors = vectorsInGroup.mapNotNull { it as? FloatVectorValue }
                        if (floatVectors.isEmpty()) {
                            emptyList()
                        } else {
                            vectorTable.select { specificValueColumn inList floatVectors }
                                .map { it[vectorTable.id] to it[specificValueColumn] }
                        }
                    }
                    VectorValue.Type.Double -> {
                        @Suppress("UNCHECKED_CAST")
                        val specificValueColumn = vectorTable.value as Column<DoubleVectorValue>
                        val doubleVectors = vectorsInGroup.mapNotNull { it as? DoubleVectorValue }
                        if (doubleVectors.isEmpty()) {
                            emptyList()
                        } else {
                            vectorTable.select { specificValueColumn inList doubleVectors }
                                .map { it[vectorTable.id] to it[specificValueColumn] }
                        }
                    }
                    VectorValue.Type.Long -> {
                        @Suppress("UNCHECKED_CAST")
                        val specificValueColumn = vectorTable.value as Column<LongVectorValue>
                        val longVectors = vectorsInGroup.mapNotNull { it as? LongVectorValue }
                        if (longVectors.isEmpty()) {
                            emptyList()
                        } else {
                            vectorTable.select { specificValueColumn inList longVectors }
                                .map { it[vectorTable.id] to it[specificValueColumn] }
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
            }

            return vectorTable
        }

        return getVectorTable(type, length) ?: createTable()
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
        return transaction {
            DoubleLiteralTable.select { DoubleLiteralTable.id inList ids }.associate {
                (DOUBLE_LITERAL_TYPE to it[DoubleLiteralTable.id]) to DoubleValue(it[DoubleLiteralTable.value])
            }
        }
    }

    override fun lookUpStringValues(ids: Set<Long>): Map<QuadValueId, StringValue> {
        return transaction {
            StringLiteralTable.select { StringLiteralTable.id inList ids }.associate {
                (STRING_LITERAL_TYPE to it[StringLiteralTable.id]) to StringValue(it[StringLiteralTable.value])
            }
        }
    }

    override fun lookUpVectorValues(ids: Set<QuadValueId>): Map<QuadValueId, VectorValue> {
        val returnMap = HashMap<QuadValueId, VectorValue>(ids.size)

        ids.groupBy { it.first }.forEach { ids ->
            val values = getVectorQuadValues(ids.key, ids.value.map { it.second })
            values.forEach {
                returnMap[ids.key to it.key] = it.value
            }
        }
        //TODO batch by type
        return returnMap
    }

    private fun getVectorQuadValues(type: Int, ids: List<Long>): Map<Long, VectorValue> {
        val internalId = -type + VECTOR_ID_OFFSET

        val properties = getVectorProperties(internalId) ?: return emptyMap()

        val vectorTable = getOrCreateVectorTable(properties.second, properties.first)

        val result = transaction {
            vectorTable.select {
                vectorTable.id inList ids
            }.map {
                it[vectorTable.id] to it[vectorTable.value]
            }.associate { (id, value) ->
                id to value
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
        return transaction {
            EntityPrefixTable.select { EntityPrefixTable.id inList ids }.associate {
                it[EntityPrefixTable.id] to it[EntityPrefixTable.prefix]
            }
        }
    }

    override fun lookUpSuffixes(ids: Set<Long>): Map<Long, String> {
        return transaction {
            EntityTable.select { EntityTable.id inList ids }.associate {
                it[EntityTable.id] to it[EntityTable.value]
            }
        }
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

        val lookUpQuadIds = transaction {
            QuadsTable.slice(QuadsTable.id, QuadsTable.sType, QuadsTable.s, QuadsTable.pType, QuadsTable.p, QuadsTable.oType, QuadsTable.o)
                .select { QuadsTable.id inList ids }.map {
                it[QuadsTable.id] to Triple(
                    (it[QuadsTable.sType] to it[QuadsTable.s]),
                    (it[QuadsTable.pType] to it[QuadsTable.p]),
                    (it[QuadsTable.oType] to it[QuadsTable.o]),
                )
            }
        }

        lookUpQuadIds.forEach {
            idCache.put(it.first, it.second)
        }

        quadIds.addAll(lookUpQuadIds)

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

    override fun filter(
        subjects: Collection<QuadValue>?,
        predicates: Collection<QuadValue>?,
        objects: Collection<QuadValue>?
    ): QuadSet {

        //if all attributes are unfiltered, do not filter
        if (subjects == null && predicates == null && objects == null) {
            return this
        }

        //if one attribute has no matches, return empty set
        if (subjects?.isEmpty() == true || predicates?.isEmpty() == true || objects?.isEmpty() == true) {
            return BasicQuadSet()
        }

        val filterIds = getOrAddQuadValueIds(
            (subjects?.toSet() ?: setOf()) + (predicates?.toSet() ?: setOf()) + (objects?.toSet() ?: setOf()),
            false
        )

        val subjectFilterIds = subjects?.mapNotNull { filterIds[it] }
        val predicateFilterIds = predicates?.mapNotNull { filterIds[it] }
        val objectFilterIds = objects?.mapNotNull { filterIds[it] }

        //no matching values
        if (subjectFilterIds?.isEmpty() == true || predicateFilterIds?.isEmpty() == true || objectFilterIds?.isEmpty() == true) {
            return BasicQuadSet()
        }

        val filter = listOfNotNull(
            subjectFilterIds?.map { (QuadsTable.sType eq it.first) and (QuadsTable.s eq it.second) }
                ?.reduce { acc, op -> acc or op },
            predicateFilterIds?.map { (QuadsTable.pType eq it.first) and (QuadsTable.p eq it.second) }
                ?.reduce { acc, op -> acc or op },
            objectFilterIds?.map { (QuadsTable.oType eq it.first) and (QuadsTable.o eq it.second) }
                ?.reduce { acc, op -> acc or op }
        ).reduce { acc, op -> acc and op }

        val quadIds = transaction {
            QuadsTable.slice(QuadsTable.id).select(filter).map { it[QuadsTable.id] }
        }

        return getIds(quadIds)
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
        val predicateId = getQuadValueId(predicate)
        if (predicateId.first == null || predicateId.second == null) {
            return BasicQuadSet()
        }

        // We are querying, so we only care about existing tables.
        val vectorTable = getVectorTable(`object`.type, `object`.length) ?: return BasicQuadSet()

        val distanceExpression = VectorDistance(vectorTable.value, `object`, distance)

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

        return getIds(quadIds)
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


//        val textIds = transaction {
//            StringLiteralTable.slice(StringLiteralTable.id).select { StringLiteralTable.value like "%${objectFilterText}%" }
//                .map { it[StringLiteralTable.id] }
//        }

        val quadIds = transaction {
            QuadsTable.slice(QuadsTable.id).select { (QuadsTable.pType eq predicatePair.first!!) and (QuadsTable.p eq predicatePair.second!!) and (QuadsTable.oType eq STRING_LITERAL_TYPE) and (QuadsTable.o inList textIds) }
                .map { it[QuadsTable.id] }
        }

        return getIds(quadIds)
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

        transaction {
            QuadsTable.slice(QuadsTable.hash).select { QuadsTable.hash inList quadIdMap.keys }.forEach {
                quadIdMap.remove(it[QuadsTable.hash])
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
}