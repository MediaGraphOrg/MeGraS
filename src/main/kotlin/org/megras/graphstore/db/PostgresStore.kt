package org.megras.graphstore.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.megras.data.graph.*
import org.megras.graphstore.Distance
import org.megras.graphstore.MutableQuadSet
import org.megras.graphstore.QuadSet


class PostgresStore(host: String = "localhost:5432/megras", user: String = "megras", password: String = "megras") : AbstractDbStore() {

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
        val value: Column<String> = varchar("value", 255).uniqueIndex()

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

    private val db : Database
    init {
        db = Database.connect("jdbc:postgresql://$host",
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
        TODO("Not yet implemented")
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
            EntityTable.batchInsert(list){
                this[EntityTable.value] = it
            }.map { it[EntityTable.id] }
        }
        return list.zip(results).toMap()
    }

    override fun insertVectorValueIds(vectorValues: Set<VectorValue>): Map<VectorValue, QuadValueId> {
        TODO("Not yet implemented")
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
        TODO("Not yet implemented")
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
            QuadsTable.select {
                QuadsTable.hash eq quadHash(s.first, s.second, p.first, p.second, o.first, o.second)
            }.firstOrNull()?.get(QuadsTable.id)
        }
    }

    override fun getId(id: Long): Quad? {
        TODO("Not yet implemented")
    }

    override fun filterSubject(subject: QuadValue): QuadSet {
        TODO("Not yet implemented")
    }

    override fun filterPredicate(predicate: QuadValue): QuadSet {
        TODO("Not yet implemented")
    }

    override fun filterObject(`object`: QuadValue): QuadSet {
        TODO("Not yet implemented")
    }

    override fun filter(
        subjects: Collection<QuadValue>?,
        predicates: Collection<QuadValue>?,
        objects: Collection<QuadValue>?
    ): QuadSet {
        TODO("Not yet implemented")
    }

    override fun toMutable(): MutableQuadSet {
        TODO("Not yet implemented")
    }

    override fun toSet(): Set<Quad> {
        TODO("Not yet implemented")
    }

    override fun plus(other: QuadSet): QuadSet {
        TODO("Not yet implemented")
    }

    override fun nearestNeighbor(predicate: QuadValue, `object`: VectorValue, count: Int, distance: Distance): QuadSet {
        TODO("Not yet implemented")
    }

    override fun textFilter(predicate: QuadValue, objectFilterText: String): QuadSet {
        TODO("Not yet implemented")
    }

    override val size: Int
        get() {
            return transaction {
                QuadsTable.selectAll().count().toInt()
            }
        }

    override fun isEmpty(): Boolean {
        TODO("Not yet implemented")
    }

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
            QuadsTable.select { QuadsTable.hash inList quadIdMap.keys }.forEach {
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