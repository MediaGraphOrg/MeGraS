package org.megras.graphstore.db.dict

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.megras.data.graph.DoubleValue
import org.megras.data.graph.StringValue
import org.megras.graphstore.db.AbstractDbStore
import org.megras.graphstore.db.QuadValueId
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.core.Schema
import com.google.common.cache.CacheBuilder

/**
 * Concrete scalar dictionary: the single authority that mints and resolves IDs
 * for prefix / suffix / string-literal / double-literal values. Owns the four
 * scalar storage tables and its own connection pool — in a distributed backend
 * this object is the only node permitted to touch these tables, so quad
 * encodings `(type, id)` are interpretable on every shard.
 *
 * All storage work uses the explicit `transaction(dictDb)` form, never the
 * ambient transaction, because a process may simultaneously hold a store
 * `Database` and this dictionary `Database`; the ambient binder would be
 * ambiguous across two pools.
 *
 * Type-discriminator constants are read from [AbstractDbStore] (single source
 * of truth). This file declares no ID-encoding policy of its own.
 *
 * Caching: value<->id caches for the four scalar kinds live HERE, on the
 * dictionary, so every caller (single-node PostgresStore forwarders and the
 * cluster substrate alike) benefits uniformly. The cache sits at the leaf
 * lookup/insert boundary, not in any caller. Vector content is sharded, so
 * vector value<->id caches belong on the owning shard, not here.
 */
class PostgresDictionary(
    host: String = "localhost:5432/megras",
    user: String = "megras",
    password: String = "megras"
) : QuadValueDictionary {

    private val dictDb: Database

    private val stringLiteralIdCache = CacheBuilder.newBuilder().maximumSize(AbstractDbStore.cacheSize).build<String, Long>()
    private val stringLiteralValueCache = CacheBuilder.newBuilder().maximumSize(AbstractDbStore.cacheSize).build<Long, String>()

    private val doubleLiteralIdCache = CacheBuilder.newBuilder().maximumSize(AbstractDbStore.cacheSize).build<Double, Long>()
    private val doubleLiteralValueCache = CacheBuilder.newBuilder().maximumSize(AbstractDbStore.cacheSize).build<Long, Double>()

    private val prefixValueCache = CacheBuilder.newBuilder().maximumSize(AbstractDbStore.cacheSize).build<Int, String>()
    private val prefixIdCache = CacheBuilder.newBuilder().maximumSize(AbstractDbStore.cacheSize).build<String, Int>()

    private val suffixValueCache = CacheBuilder.newBuilder().maximumSize(AbstractDbStore.cacheSize).build<Long, String>()
    private val suffixIdCache = CacheBuilder.newBuilder().maximumSize(AbstractDbStore.cacheSize).build<String, Long>()

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
        dictDb = Database.connect(HikariDataSource(config))
        transaction(dictDb) {
            val schema = Schema("megras")
            SchemaUtils.createSchema(schema)
            SchemaUtils.setSchema(schema)
        }
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

    override fun setup() {
        transaction(dictDb) {
            SchemaUtils.create(StringLiteralTable, DoubleLiteralTable, EntityPrefixTable, EntityTable)
            //TODO add this in a more idiomatic exposed way
            exec("ALTER TABLE megras.literal_string ADD COLUMN IF NOT EXISTS ts tsvector GENERATED ALWAYS AS (to_tsvector('english', value)) STORED;")
            exec("CREATE INDEX IF NOT EXISTS ts_idx ON megras.literal_string USING GIN (ts);")
        }
    }

    override fun lookUpDoubleValueIds(doubleValues: Set<DoubleValue>): Map<DoubleValue, QuadValueId> {
        if (doubleValues.isEmpty()) return emptyMap()
        val result = mutableMapOf<DoubleValue, QuadValueId>()
        val misses = mutableListOf<DoubleValue>()
        for (v in doubleValues) {
            val cached = doubleLiteralIdCache.getIfPresent(v.value)
            if (cached != null) result[v] = AbstractDbStore.DOUBLE_LITERAL_TYPE to cached else misses.add(v)
        }
        if (misses.isEmpty()) return result
        misses.map { it.value }.chunked(10000).forEach { chunk ->
            transaction(dictDb) {
                DoubleLiteralTable.selectAll().where { DoubleLiteralTable.value inList chunk }.forEach {
                    val dv = DoubleValue(it[DoubleLiteralTable.value])
                    val id = it[DoubleLiteralTable.id]
                    result[dv] = AbstractDbStore.DOUBLE_LITERAL_TYPE to id
                    doubleLiteralIdCache.put(dv.value, id)
                    doubleLiteralValueCache.put(id, dv.value)
                }
            }
        }
        return result
    }

    override fun lookUpStringValueIds(stringValues: Set<StringValue>): Map<StringValue, QuadValueId> {
        if (stringValues.isEmpty()) return emptyMap()
        val result = mutableMapOf<StringValue, QuadValueId>()
        val misses = mutableListOf<StringValue>()
        for (v in stringValues) {
            val cached = stringLiteralIdCache.getIfPresent(v.value)
            if (cached != null) result[v] = AbstractDbStore.STRING_LITERAL_TYPE to cached else misses.add(v)
        }
        if (misses.isEmpty()) return result
        misses.map { it.value }.chunked(10000).forEach { chunk ->
            transaction(dictDb) {
                StringLiteralTable.selectAll().where { StringLiteralTable.value inList chunk }.forEach {
                    val sv = StringValue(it[StringLiteralTable.value])
                    val id = it[StringLiteralTable.id]
                    result[sv] = AbstractDbStore.STRING_LITERAL_TYPE to id
                    stringLiteralIdCache.put(sv.value, id)
                    stringLiteralValueCache.put(id, sv.value)
                }
            }
        }
        return result
    }

    override fun lookUpPrefixIds(prefixValues: Set<String>): Map<String, Int> {
        if (prefixValues.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, Int>()
        val misses = mutableListOf<String>()
        for (v in prefixValues) {
            val cached = prefixIdCache.getIfPresent(v)
            if (cached != null) result[v] = cached else misses.add(v)
        }
        if (misses.isEmpty()) return result
        misses.chunked(10000).forEach { chunk ->
            transaction(dictDb) {
                EntityPrefixTable.selectAll().where { EntityPrefixTable.prefix inList chunk }.forEach {
                    val p = it[EntityPrefixTable.prefix]
                    val id = it[EntityPrefixTable.id]
                    result[p] = id
                    prefixIdCache.put(p, id)
                    prefixValueCache.put(id, p)
                }
            }
        }
        return result
    }

    override fun lookUpSuffixIds(suffixValues: Set<String>): Map<String, Long> {
        if (suffixValues.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, Long>()
        val misses = mutableListOf<String>()
        for (v in suffixValues) {
            val cached = suffixIdCache.getIfPresent(v)
            if (cached != null) result[v] = cached else misses.add(v)
        }
        if (misses.isEmpty()) return result
        misses.chunked(10000).forEach { chunk ->
            transaction(dictDb) {
                EntityTable.selectAll().where { EntityTable.value inList chunk }.forEach {
                    val s = it[EntityTable.value]
                    val id = it[EntityTable.id]
                    result[s] = id
                    suffixIdCache.put(s, id)
                    suffixValueCache.put(id, s)
                }
            }
        }
        return result
    }

    override fun insertDoubleValues(doubleValues: Set<DoubleValue>): Map<DoubleValue, QuadValueId> {
        val list = doubleValues.toList()
        val results = transaction(dictDb) {
            DoubleLiteralTable.batchInsert(list) {
                this[DoubleLiteralTable.value] = it.value
            }.map { AbstractDbStore.DOUBLE_LITERAL_TYPE to it[DoubleLiteralTable.id] }
        }
        val resultMap = list.zip(results).toMap()
        resultMap.forEach { (v, id) ->
            doubleLiteralIdCache.put(v.value, id.second)
            doubleLiteralValueCache.put(id.second, v.value)
        }
        return resultMap
    }

    override fun insertStringValues(stringValues: Set<StringValue>): Map<StringValue, QuadValueId> {
        val list = stringValues.toList()
        val results = transaction(dictDb) {
            StringLiteralTable.batchInsert(list) {
                this[StringLiteralTable.value] = it.value
            }.map { AbstractDbStore.STRING_LITERAL_TYPE to it[StringLiteralTable.id] }
        }
        val resultMap = list.zip(results).toMap()
        resultMap.forEach { (v, id) ->
            stringLiteralIdCache.put(v.value, id.second)
            stringLiteralValueCache.put(id.second, v.value)
        }
        return resultMap
    }

    override fun insertPrefixValues(prefixValues: Set<String>): Map<String, Int> {
        val list = prefixValues.toList()
        val results = transaction(dictDb) {
            EntityPrefixTable.batchInsert(list) {
                this[EntityPrefixTable.prefix] = it
            }.map { it[EntityPrefixTable.id] }
        }
        val resultMap = list.zip(results).toMap()
        resultMap.forEach { (v, id) ->
            prefixIdCache.put(v, id)
            prefixValueCache.put(id, v)
        }
        return resultMap
    }

    override fun insertSuffixValues(suffixValues: Set<String>): Map<String, Long> {
        val list = suffixValues.toList()
        val results = transaction(dictDb) {
            EntityTable.batchInsert(list) {
                this[EntityTable.value] = it
            }.map { it[EntityTable.id] }
        }
        val resultMap = list.zip(results).toMap()
        resultMap.forEach { (v, id) ->
            suffixIdCache.put(v, id)
            suffixValueCache.put(id, v)
        }
        return resultMap
    }

    override fun lookUpDoubleValues(ids: Set<Long>): Map<QuadValueId, DoubleValue> {
        if (ids.isEmpty()) return emptyMap()
        val result = mutableMapOf<QuadValueId, DoubleValue>()
        val misses = mutableListOf<Long>()
        for (id in ids) {
            val cached = doubleLiteralValueCache.getIfPresent(id)
            if (cached != null) result[AbstractDbStore.DOUBLE_LITERAL_TYPE to id] = DoubleValue(cached) else misses.add(id)
        }
        if (misses.isEmpty()) return result
        misses.chunked(10000).forEach { chunk ->
            transaction(dictDb) {
                DoubleLiteralTable.selectAll().where { DoubleLiteralTable.id inList chunk }.forEach {
                    val id = it[DoubleLiteralTable.id]
                    val dv = DoubleValue(it[DoubleLiteralTable.value])
                    result[AbstractDbStore.DOUBLE_LITERAL_TYPE to id] = dv
                    doubleLiteralValueCache.put(id, dv.value)
                    doubleLiteralIdCache.put(dv.value, id)
                }
            }
        }
        return result
    }

    override fun lookUpStringValues(ids: Set<Long>): Map<QuadValueId, StringValue> {
        if (ids.isEmpty()) return emptyMap()
        val result = mutableMapOf<QuadValueId, StringValue>()
        val misses = mutableListOf<Long>()
        for (id in ids) {
            val cached = stringLiteralValueCache.getIfPresent(id)
            if (cached != null) result[AbstractDbStore.STRING_LITERAL_TYPE to id] = StringValue(cached) else misses.add(id)
        }
        if (misses.isEmpty()) return result
        misses.chunked(10000).forEach { chunk ->
            transaction(dictDb) {
                StringLiteralTable.selectAll().where { StringLiteralTable.id inList chunk }.forEach {
                    val id = it[StringLiteralTable.id]
                    val sv = StringValue(it[StringLiteralTable.value])
                    result[AbstractDbStore.STRING_LITERAL_TYPE to id] = sv
                    stringLiteralValueCache.put(id, sv.value)
                    stringLiteralIdCache.put(sv.value, id)
                }
            }
        }
        return result
    }

    override fun lookUpPrefixes(ids: Set<Int>): Map<Int, String> {
        if (ids.isEmpty()) return emptyMap()
        val result = mutableMapOf<Int, String>()
        val misses = mutableListOf<Int>()
        for (id in ids) {
            val cached = prefixValueCache.getIfPresent(id)
            if (cached != null) result[id] = cached else misses.add(id)
        }
        if (misses.isEmpty()) return result
        misses.chunked(10000).forEach { chunk ->
            transaction(dictDb) {
                EntityPrefixTable.selectAll().where { EntityPrefixTable.id inList chunk }.forEach {
                    val id = it[EntityPrefixTable.id]
                    val p = it[EntityPrefixTable.prefix]
                    result[id] = p
                    prefixValueCache.put(id, p)
                    prefixIdCache.put(p, id)
                }
            }
        }
        return result
    }

    override fun lookUpSuffixes(ids: Set<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        val result = mutableMapOf<Long, String>()
        val misses = mutableListOf<Long>()
        for (id in ids) {
            val cached = suffixValueCache.getIfPresent(id)
            if (cached != null) result[id] = cached else misses.add(id)
        }
        if (misses.isEmpty()) return result
        misses.chunked(10000).forEach { chunk ->
            transaction(dictDb) {
                EntityTable.selectAll().where { EntityTable.id inList chunk }.forEach {
                    val id = it[EntityTable.id]
                    val s = it[EntityTable.value]
                    result[id] = s
                    suffixValueCache.put(id, s)
                    suffixIdCache.put(s, id)
                }
            }
        }
        return result
    }

    override fun lookUpStringValueIdsByText(objectFilterText: String): Set<Long> {
        return transaction(dictDb) {
            StringLiteralTable.select(StringLiteralTable.id).where(FullTextSearch(objectFilterText))
                .map { it[StringLiteralTable.id] }
                .toSet()
        }
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
}
