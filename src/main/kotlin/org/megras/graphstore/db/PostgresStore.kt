package org.megras.graphstore.db

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

        override val primaryKey = PrimaryKey(QuadsTable.id)
    }

    object DoubleLiteralTable : Table("literal_double") {
        val id: Column<Long> = long("id").autoIncrement().uniqueIndex()
        val value: Column<Double> = double("value").uniqueIndex()

        override val primaryKey = PrimaryKey(QuadsTable.id)
    }

    object EntityPrefixTable : Table("entity_prefix") {
        val id: Column<Int> = integer("id").autoIncrement().uniqueIndex()
        val prefix: Column<String> = varchar("prefix", 255).uniqueIndex()

        override val primaryKey = PrimaryKey(QuadsTable.id)
    }

    object EntityTable : Table("entity") {
        val id: Column<Long> = long("id").autoIncrement().uniqueIndex()
        val value: Column<String> = varchar("value", 255).uniqueIndex()

        override val primaryKey = PrimaryKey(QuadsTable.id)
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
            SchemaUtils.create(QuadsTable, StringLiteralTable, DoubleLiteralTable, EntityPrefixTable, EntityTable)
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
        return emptyMap() //FIXME currently unsupported
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
        return emptyMap() //FIXME currently unsupported
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
        return emptyMap() //FIXME currently unsupported
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

    private fun getIds(ids: Collection<Long>): QuadSet { //TODO caching

        if (ids.isEmpty()) {
            return BasicQuadSet()
        }

        val quadIds = transaction {
            QuadsTable.select { QuadsTable.id inList ids }.map {
                it[QuadsTable.id] to Triple(
                    (it[QuadsTable.sType] to it[QuadsTable.s]),
                    (it[QuadsTable.pType] to it[QuadsTable.p]),
                    (it[QuadsTable.oType] to it[QuadsTable.o]),
                )
            }
        }

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

    override fun nearestNeighbor(predicate: QuadValue, `object`: VectorValue, count: Int, distance: Distance): QuadSet {
        return BasicQuadSet() //FIXME currently unsupported
    }

    override fun textFilter(predicate: QuadValue, objectFilterText: String): QuadSet {

        val predicatePair = getQuadValueId(predicate)

        if (predicatePair.first == null || predicatePair.second == null) { //unknown predicate, can't have matching quads
            return BasicQuadSet()
        }

        val textIds = transaction {
            StringLiteralTable.slice(StringLiteralTable.id).select { StringLiteralTable.value like "%${objectFilterText}%" }
                .map { it[StringLiteralTable.id] }
        }

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
        TODO("Not yet implemented")
    }

    override fun addAll(elements: Collection<Quad>): Boolean {

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