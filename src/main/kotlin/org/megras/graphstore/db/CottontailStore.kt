package org.megras.graphstore.db

import com.google.common.cache.CacheBuilder
import io.grpc.ManagedChannelBuilder
import io.grpc.StatusRuntimeException
import org.megras.data.graph.*
import org.megras.data.schema.MeGraS
import org.megras.graphstore.*
import org.megras.util.extensions.toBase64
import org.vitrivr.cottontail.client.SimpleClient
import org.vitrivr.cottontail.client.language.basics.Direction
import org.vitrivr.cottontail.client.language.basics.Type
import org.vitrivr.cottontail.client.language.basics.predicate.And
import org.vitrivr.cottontail.client.language.basics.predicate.Expression
import org.vitrivr.cottontail.client.language.basics.predicate.Or
import org.vitrivr.cottontail.client.language.basics.predicate.Predicate
import org.vitrivr.cottontail.client.language.ddl.CreateEntity
import org.vitrivr.cottontail.client.language.ddl.CreateIndex
import org.vitrivr.cottontail.client.language.ddl.CreateSchema
import org.vitrivr.cottontail.client.language.dml.BatchInsert
import org.vitrivr.cottontail.client.language.dml.Delete
import org.vitrivr.cottontail.client.language.dml.Insert
import org.vitrivr.cottontail.client.language.dql.Query
import org.vitrivr.cottontail.grpc.CottontailGrpc
import java.nio.ByteBuffer

typealias QuadValueId = Pair<Int, Long>

class CottontailStore(host: String = "localhost", port: Int = 1865) : AbstractDbStore() {

    private val channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build()

    private val client = SimpleClient(channel)


    override fun setup() {

        fun catchExists(lambda: () -> Unit) {
            try {
                lambda()
            } catch (e: StatusRuntimeException) {
                if (e.message?.contains("ALREADY_EXISTS") == false) {
                    throw e
                }
            }
        }

        catchExists { client.create(CreateSchema("megras")) }

        catchExists {
            client.create(
                CreateEntity("megras.quads")
                    .column("id", Type.LONG, autoIncrement = true)
                    .column("s_type", Type.INTEGER)
                    .column("s", Type.LONG)
                    .column("p_type", Type.INTEGER)
                    .column("p", Type.LONG)
                    .column("o_type", Type.INTEGER)
                    .column("o", Type.LONG)
                    .column("hash", Type.STRING)
            )
        }

        catchExists { client.create(CreateIndex("megras.quads", "id", CottontailGrpc.IndexType.BTREE_UQ)) }
//        catchExists { client.create(CreateIndex("megras.quads", "s_type", CottontailGrpc.IndexType.BTREE)) }
        catchExists { client.create(CreateIndex("megras.quads", "s", CottontailGrpc.IndexType.BTREE)) }
//        catchExists { client.create(CreateIndex("megras.quads", "p_type", CottontailGrpc.IndexType.BTREE)) }
        catchExists { client.create(CreateIndex("megras.quads", "p", CottontailGrpc.IndexType.BTREE)) }
//        catchExists { client.create(CreateIndex("megras.quads", "o_type", CottontailGrpc.IndexType.BTREE)) }
        catchExists { client.create(CreateIndex("megras.quads", "o", CottontailGrpc.IndexType.BTREE)) }
        catchExists { client.create(CreateIndex("megras.quads", "hash", CottontailGrpc.IndexType.BTREE_UQ)) }

        catchExists {
            client.create(
                CreateEntity("megras.literal_string")
                    .column("id", Type.LONG, autoIncrement = true)
                    .column("value", Type.STRING)
            )
        }

        catchExists { client.create(CreateIndex("megras.literal_string", "id", CottontailGrpc.IndexType.BTREE_UQ)) }
        catchExists { client.create(CreateIndex("megras.literal_string", "value", CottontailGrpc.IndexType.BTREE)) }
        catchExists { client.create(CreateIndex("megras.literal_string", "value", CottontailGrpc.IndexType.LUCENE)) }

        catchExists {
            client.create(
                CreateEntity("megras.literal_double")
                    .column("id", Type.LONG, autoIncrement = true)
                    .column("value", Type.DOUBLE)
            )
        }

        catchExists { client.create(CreateIndex("megras.literal_double", "id", CottontailGrpc.IndexType.BTREE_UQ)) }
        catchExists { client.create(CreateIndex("megras.literal_double", "value", CottontailGrpc.IndexType.BTREE)) }

        catchExists {
            client.create(
                CreateEntity("megras.entity_prefix")
                    .column("id", Type.INTEGER, autoIncrement = true)
                    .column("prefix", Type.STRING)
            )
        }

        catchExists { client.create(CreateIndex("megras.entity_prefix", "id", CottontailGrpc.IndexType.BTREE_UQ)) }
        catchExists { client.create(CreateIndex("megras.entity_prefix", "prefix", CottontailGrpc.IndexType.BTREE)) }

        catchExists {
            client.create(
                CreateEntity("megras.entity")
                    .column("id", Type.LONG, autoIncrement = true)
                    .column("value", Type.STRING)
            )
        }

        catchExists { client.create(CreateIndex("megras.entity", "id", CottontailGrpc.IndexType.BTREE_UQ)) }
        catchExists { client.create(CreateIndex("megras.entity", "value", CottontailGrpc.IndexType.BTREE)) }


        catchExists {
            client.create(
                CreateEntity("megras.vector_types")
                    .column("id", Type.INTEGER, autoIncrement = true)
                    .column("type", Type.INTEGER)
                    .column("length", Type.INTEGER)
            )

        }

        catchExists { client.create(CreateIndex("megras.vector_types", "id", CottontailGrpc.IndexType.BTREE_UQ)) }
        catchExists { client.create(CreateIndex("megras.vector_types", "type", CottontailGrpc.IndexType.BTREE)) }
        catchExists { client.create(CreateIndex("megras.vector_types", "length", CottontailGrpc.IndexType.BTREE)) }


    }


    private fun getQuadValueIds(quadValues: Collection<QuadValue>): Map<QuadValue, QuadValueId> {

        if (quadValues.isEmpty()) {
            return emptyMap()
        }

        val returnMap = mutableMapOf<QuadValue, QuadValueId>()

        val doubleValues = mutableSetOf<DoubleValue>()
        val stringValues = mutableSetOf<StringValue>()
        val uriValues = mutableSetOf<URIValue>()
        val vectorValues = mutableSetOf<VectorValue>()

        //sort by type
        quadValues.forEach {
            when (it) {
                is DoubleValue -> doubleValues.add(it)
                is LongValue -> returnMap[it] = LONG_LITERAL_TYPE to it.value
                is StringValue -> stringValues.add(it)
                is URIValue -> uriValues.add(it)
                is VectorValue -> vectorValues.add(it)
            }
        }

        //cache lookup
        doubleValues.removeIf {
            val cached = doubleLiteralIdCache.getIfPresent(it) ?: return@removeIf false
            returnMap[it] = DOUBLE_LITERAL_TYPE to cached
            true
        }

        stringValues.removeIf {
            val cached = stringLiteralIdCache.getIfPresent(it) ?: return@removeIf false
            returnMap[it] = STRING_LITERAL_TYPE to cached
            true
        }

        uriValues.removeIf {
            val cached = uriValueValueCache.getIfPresent(it) ?: return@removeIf false
            returnMap[it] = cached
            true
        }

        vectorValues.removeIf {
            val cached = vectorValueValueCache.getIfPresent(it) ?: return@removeIf false
            returnMap[it] = cached
            true
        }


        //database lookup

        if (doubleValues.isNotEmpty()) {
            val result = client.query(
                Query("megras.literal_double").select("*").where(
                    Expression("value", "in", doubleValues.map { it.value })
                )
            )

            while (result.hasNext()) {
                val tuple = result.next()
                val id = tuple.asLong("id") ?: continue
                val value = tuple.asDouble("value") ?: continue
                doubleLiteralValueCache.put(id, value)
                doubleLiteralIdCache.put(value, id)
                val v = DoubleValue(value)
                returnMap[v] = DOUBLE_LITERAL_TYPE to id
                doubleValues.remove(v)
            }

        }

        if (stringValues.isNotEmpty()) {

            val result = client.query(
                Query("megras.literal_string")
                    .select("*")
                    .where(Expression("value", "in", stringValues.map { it.value }))
            )

            while (result.hasNext()) {
                val tuple = result.next()
                val id = tuple.asLong("id") ?: continue
                val value = tuple.asString("value") ?: continue
                stringLiteralValueCache.put(id, value)
                stringLiteralIdCache.put(value, id)
                val v = StringValue(value)
                returnMap[v] = STRING_LITERAL_TYPE to id
                stringValues.remove(v)
            }

        }

        if (uriValues.isNotEmpty()) {

            val prefixValues =
                uriValues.asSequence().filter { it !is LocalQuadValue }.map { it.prefix() }.toMutableSet()
            val suffixValues = uriValues.map { it.suffix() }.toMutableSet()

            val prefixIdMap = mutableMapOf<String, Int>()
            val suffixIdMap = mutableMapOf<String, Long>()

            prefixValues.removeIf {
                val cached = prefixIdCache.getIfPresent(it) ?: return@removeIf false
                prefixIdMap[it] = cached
                true
            }

            suffixValues.removeIf {
                val cached = suffixIdCache.getIfPresent(it) ?: return@removeIf false
                suffixIdMap[it] = cached
                true
            }

            if (prefixValues.isNotEmpty()) {
                val result = client.query(
                    Query("megras.entity_prefix").select("*").where(
                        Expression("prefix", "in", prefixValues)
                    )
                )

                while (result.hasNext()) {
                    val tuple = result.next()
                    val id = tuple.asInt("id") ?: continue
                    val value = tuple.asString("prefix") ?: continue
                    prefixValueCache.put(id, value)
                    prefixIdCache.put(value, id)
                    prefixIdMap[value] = id
                    prefixValues.remove(value)
                }
            }

            if (suffixValues.isNotEmpty()) {

                val result = client.query(
                    Query("megras.entity").select("*").where(
                        Expression("value", "in", suffixValues)
                    )
                )

                while (result.hasNext()) {
                    val tuple = result.next()
                    val id = tuple.asLong("id") ?: continue
                    val value = tuple.asString("value") ?: continue
                    suffixIdCache.put(value, id)
                    suffixValueCache.put(id, value)
                    suffixIdMap[value] = id
                    suffixValues.remove(value)
                }
            }

            //combine entries
            uriValues.forEach {
                val s = if (it is LocalQuadValue) LOCAL_URI_TYPE else prefixIdMap[it.prefix()]
                val p = suffixIdMap[it.suffix()]
                if (s != null && p != null) {
                    returnMap[it] = s to p
                }
            }
        }

        if (vectorValues.isNotEmpty()) {

            vectorValues.groupBy { it.type to it.length }.forEach { (properties, v) ->

                val vectors = v.toMutableSet()

                val entityId = getOrCreateVectorEntity(properties.first, properties.second)
                val name = "megras.vector_values_${entityId}"


                val result = when (properties.first) {
                    VectorValue.Type.Double -> {
                        val v = vectors.map { (it as DoubleVectorValue).vector }
                        client.query(
                            Query(name).select("*").where(Expression("value", "in", v))
                        )
                    }

                    VectorValue.Type.Long -> {
                        val v = vectors.map { (it as LongVectorValue).vector }
                        client.query(
                            Query(name).select("*").where(Expression("value", "in", v))
                        )
                    }
                }

                while (result.hasNext()) {
                    val tuple = result.next()
                    val id = tuple.asLong("id")!!
                    val value = when (properties.first) {
                        VectorValue.Type.Double -> DoubleVectorValue(tuple.asDoubleVector("value")!!)
                        VectorValue.Type.Long -> LongVectorValue(tuple.asLongVector("value")!!)
                    }
                    val pair = (-entityId + VECTOR_ID_OFFSET) to id
                    vectorValueValueCache.put(value, pair)
                    vectorValueIdCache.put(pair, value)
                    returnMap[value] = pair
                    vectors.remove(value)
                }
            }

        }

        return returnMap

    }

    private fun getOrAddQuadValueIds(quadValues: Collection<QuadValue>): Map<QuadValue, QuadValueId> {

        if (quadValues.isEmpty()) {
            return emptyMap()
        }

        val returnMap = mutableMapOf<QuadValue, QuadValueId>()

        val doubleValues = mutableSetOf<DoubleValue>()
        val stringValues = mutableSetOf<StringValue>()
        val uriValues = mutableSetOf<URIValue>()
        val vectorValues = mutableSetOf<VectorValue>()

        //sort by type
        quadValues.forEach {
            when (it) {
                is DoubleValue -> doubleValues.add(it)
                is LongValue -> returnMap[it] = LONG_LITERAL_TYPE to it.value
                is StringValue -> stringValues.add(it)
                is URIValue -> uriValues.add(it)
                is VectorValue -> vectorValues.add(it)
            }
        }

        //cache lookup
        doubleValues.removeIf {
            val cached = doubleLiteralIdCache.getIfPresent(it) ?: return@removeIf false
            returnMap[it] = DOUBLE_LITERAL_TYPE to cached
            true
        }

        stringValues.removeIf {
            val cached = stringLiteralIdCache.getIfPresent(it) ?: return@removeIf false
            returnMap[it] = STRING_LITERAL_TYPE to cached
            true
        }

        uriValues.removeIf {
            val cached = uriValueValueCache.getIfPresent(it) ?: return@removeIf false
            returnMap[it] = cached
            true
        }

        vectorValues.removeIf {
            val cached = vectorValueValueCache.getIfPresent(it) ?: return@removeIf false
            returnMap[it] = cached
            true
        }


        //database lookup

        if (doubleValues.isNotEmpty()) {
            val result = client.query(
                Query("megras.literal_double").select("*").where(
                    Expression("value", "in", doubleValues.map { it.value })
                )
            )

            while (result.hasNext()) {
                val tuple = result.next()
                val id = tuple.asLong("id") ?: continue
                val value = tuple.asDouble("value") ?: continue
                doubleLiteralValueCache.put(id, value)
                doubleLiteralIdCache.put(value, id)
                val v = DoubleValue(value)
                returnMap[v] = DOUBLE_LITERAL_TYPE to id
                doubleValues.remove(v)
            }

            if (doubleValues.isNotEmpty()) {

                val batchInsert = BatchInsert("megras.literal_double").columns("value")
                doubleValues.forEach {
                    require(batchInsert.append(it.value)) { "could not add value to batch, try reducing batch size" }
                }

                client.insert(batchInsert)

                val result = client.query(
                    Query("megras.literal_double").select("*").where(
                        Expression("value", "in", doubleValues.map { it.value })
                    )
                )

                while (result.hasNext()) {
                    val tuple = result.next()
                    val id = tuple.asLong("id")!!
                    val value = tuple.asDouble("value")!!
                    doubleLiteralValueCache.put(id, value)
                    doubleLiteralIdCache.put(value, id)
                    val v = DoubleValue(value)
                    returnMap[v] = DOUBLE_LITERAL_TYPE to id
                    doubleValues.remove(v)
                }
            }

        }

        if (stringValues.isNotEmpty()) {

            val result = client.query(
                Query("megras.literal_string")
                    .select("*")
                    .where(Expression("value", "in", stringValues.map { it.value }))
            )

            while (result.hasNext()) {
                val tuple = result.next()
                val id = tuple.asLong("id") ?: continue
                val value = tuple.asString("value") ?: continue
                stringLiteralValueCache.put(id, value)
                stringLiteralIdCache.put(value, id)
                val v = StringValue(value)
                returnMap[v] = STRING_LITERAL_TYPE to id
                stringValues.remove(v)
            }

            if (stringValues.isNotEmpty()) {

                val batchInsert = BatchInsert("megras.literal_string").columns("value")
                stringValues.forEach {
                    require(batchInsert.append(it.value)) { "could not add value to batch, try reducing batch size" }
                }
                client.insert(batchInsert)

                val result = client.query(
                    Query("megras.literal_string")
                        .select("*")
                        .where(Expression("value", "in", stringValues.map { it.value }))
                )

                while (result.hasNext()) {
                    val tuple = result.next()
                    val id = tuple.asLong("id")!!
                    val value = tuple.asString("value")!!
                    stringLiteralValueCache.put(id, value)
                    stringLiteralIdCache.put(value, id)
                    val v = StringValue(value)
                    returnMap[v] = STRING_LITERAL_TYPE to id
                    stringValues.remove(v)
                }
            }

        }

        if (uriValues.isNotEmpty()) {

            val prefixValues =
                uriValues.asSequence().filter { it !is LocalQuadValue }.map { it.prefix() }.toMutableSet()
            val suffixValues = uriValues.map { it.suffix() }.toMutableSet()

            val prefixIdMap = mutableMapOf<String, Int>()
            val suffixIdMap = mutableMapOf<String, Long>()

            prefixValues.removeIf {
                val cached = prefixIdCache.getIfPresent(it) ?: return@removeIf false
                prefixIdMap[it] = cached
                true
            }

            suffixValues.removeIf {
                val cached = suffixIdCache.getIfPresent(it) ?: return@removeIf false
                suffixIdMap[it] = cached
                true
            }

            if (prefixValues.isNotEmpty()) {
                val result = client.query(
                    Query("megras.entity_prefix").select("*").where(
                        Expression("prefix", "in", prefixValues)
                    )
                )

                while (result.hasNext()) {
                    val tuple = result.next()
                    val id = tuple.asInt("id") ?: continue
                    val value = tuple.asString("prefix") ?: continue
                    prefixValueCache.put(id, value)
                    prefixIdCache.put(value, id)
                    prefixIdMap[value] = id
                    prefixValues.remove(value)
                }

                if (prefixValues.isNotEmpty()) {

                    val batchInsert = BatchInsert("megras.entity_prefix").columns("prefix")
                    prefixValues.forEach { value ->
                        require(batchInsert.append(value)) { "could not add value to batch, try reducing batch size" }
                    }
                    client.insert(batchInsert)

                    val result = client.query(
                        Query("megras.entity_prefix").select("*").where(
                            Expression("prefix", "in", prefixValues)
                        )
                    )

                    while (result.hasNext()) {
                        val tuple = result.next()
                        val id = tuple.asInt("id")!!
                        val value = tuple.asString("prefix")!!
                        prefixValueCache.put(id, value)
                        prefixIdCache.put(value, id)
                        prefixIdMap[value] = id
                        prefixValues.remove(value)
                    }

                }

            }

            if (suffixValues.isNotEmpty()) {

                val result = client.query(
                    Query("megras.entity").select("*").where(
                        Expression("value", "in", suffixValues)
                    )
                )

                while (result.hasNext()) {
                    val tuple = result.next()
                    val id = tuple.asLong("id") ?: continue
                    val value = tuple.asString("value") ?: continue
                    suffixIdCache.put(value, id)
                    suffixValueCache.put(id, value)
                    suffixIdMap[value] = id
                    suffixValues.remove(value)
                }

                if (suffixValues.isNotEmpty()) {

                    val batchInsert = BatchInsert("megras.entity").columns("value")
                    suffixValues.forEach { value ->
                        require(batchInsert.append(value)) { "could not add value to batch, try reducing batch size" }
                    }
                    client.insert(batchInsert)
                    val result = client.query(
                        Query("megras.entity").select("*").where(
                            Expression("value", "in", suffixValues)
                        )
                    )

                    while (result.hasNext()) {
                        val tuple = result.next()
                        val id = tuple.asLong("id")!!
                        val value = tuple.asString("value")!!
                        suffixIdCache.put(value, id)
                        suffixValueCache.put(id, value)
                        suffixIdMap[value] = id
                        suffixValues.remove(value)
                    }

                }

            }

            //combine entries
            uriValues.forEach {
                returnMap[it] =
                    (if (it is LocalQuadValue) LOCAL_URI_TYPE else prefixIdMap[it.prefix()]!!) to suffixIdMap[it.suffix()]!!
            }

        }


        if (vectorValues.isNotEmpty()) {

            vectorValues.groupBy { it.type to it.length }.forEach { (properties, v) ->

                val vectors = v.toMutableSet()

                val entityId = getOrCreateVectorEntity(properties.first, properties.second)
                val name = "megras.vector_values_${entityId}"


                val result = when (properties.first) {
                    VectorValue.Type.Double -> {
                        val v = vectors.map { (it as DoubleVectorValue).vector }
                        client.query(
                            Query(name).select("*").where(Expression("value", "in", v))
                        )
                    }

                    VectorValue.Type.Long -> {
                        val v = vectors.map { (it as LongVectorValue).vector }
                        client.query(
                            Query(name).select("*").where(Expression("value", "in", v))
                        )
                    }
                }

                while (result.hasNext()) {
                    val tuple = result.next()
                    val id = tuple.asLong("id")!!
                    val value = when (properties.first) {
                        VectorValue.Type.Double -> DoubleVectorValue(tuple.asDoubleVector("value")!!)
                        VectorValue.Type.Long -> LongVectorValue(tuple.asLongVector("value")!!)
                    }
                    val pair = (-entityId + VECTOR_ID_OFFSET) to id
                    vectorValueValueCache.put(value, pair)
                    vectorValueIdCache.put(pair, value)
                    returnMap[value] = pair
                    vectors.remove(value)
                }

                if (vectors.isNotEmpty()) {

                    val insert = BatchInsert(name).columns("value")
                    vectors.forEach {
                        require(
                            insert.append(
                                when (properties.first) {
                                    VectorValue.Type.Double -> (it as DoubleVectorValue).vector
                                    VectorValue.Type.Long -> (it as LongVectorValue).vector
                                }
                            )
                        ) { "could not add value to batch, try reducing batch size" }

                    }
                    client.insert(insert)


                    val result = when (properties.first) {
                        VectorValue.Type.Double -> {
                            val v = vectors.map { (it as DoubleVectorValue).vector }
                            client.query(
                                Query(name).select("*").where(Expression("value", "in", v))
                            )
                        }

                        VectorValue.Type.Long -> {
                            val v = vectors.map { (it as LongVectorValue).vector }
                            client.query(
                                Query(name).select("*").where(Expression("value", "in", v))
                            )
                        }
                    }

                    while (result.hasNext()) {
                        val tuple = result.next()
                        val id = tuple.asLong("id")!!
                        val value = when (properties.first) {
                            VectorValue.Type.Double -> DoubleVectorValue(tuple.asDoubleVector("value")!!)
                            VectorValue.Type.Long -> LongVectorValue(tuple.asLongVector("value")!!)
                        }
                        val pair = (-entityId + VECTOR_ID_OFFSET) to id
                        vectorValueValueCache.put(value, pair)
                        vectorValueIdCache.put(pair, value)
                        returnMap[value] = pair
                        vectors.remove(value)
                    }

                }


            }

        }

        return returnMap

    }


    private fun getQuadValues(ids: Collection<QuadValueId>): Map<QuadValueId, QuadValue> {

        val returnMap = mutableMapOf<QuadValueId, QuadValue>()

        ids.groupBy { it.first }.forEach { (type, groupedPairs) ->

            when {
                type == DOUBLE_LITERAL_TYPE -> {
                    val pairSet = groupedPairs.toMutableSet()
                    pairSet.removeIf {
                        val cached = doubleLiteralValueCache.getIfPresent(it.second)
                        if (cached != null) {
                            returnMap[it] = DoubleValue(cached)
                            true
                        } else {
                            false
                        }
                    }
                    if (pairSet.isNotEmpty()) {

                        val result = client.query(
                            Query("megras.literal_double")
                                .select("*")
                                .where(Expression("id", "in", pairSet.map { it.second }))
                        )

                        while (result.hasNext()) {
                            val tuple = result.next()
                            val value = tuple.asDouble("value") ?: continue
                            val id = tuple.asLong("id") ?: continue

                            doubleLiteralIdCache.put(value, id)
                            doubleLiteralValueCache.put(id, value)
                            returnMap[DOUBLE_LITERAL_TYPE to id] = DoubleValue(value)
                        }
                    }
                }

                type == LONG_LITERAL_TYPE -> {
                    returnMap.putAll(groupedPairs.associateWith { LongValue(it.second) })
                }

                type == STRING_LITERAL_TYPE -> {
                    val pairSet = groupedPairs.toMutableSet()
                    pairSet.removeIf {
                        val cached = stringLiteralValueCache.getIfPresent(it.second)
                        if (cached != null) {
                            returnMap[it] = StringValue(cached)
                            true
                        } else {
                            false
                        }
                    }

                    if (pairSet.isNotEmpty()) {
                        val result = client.query(
                            Query("megras.literal_string")
                                .select("*")
                                .where(Expression("id", "in", pairSet.map { it.second }))
                        )
                        while (result.hasNext()) {
                            val tuple = result.next()
                            val value = tuple.asString("value") ?: continue
                            val id = tuple.asLong("id") ?: continue

                            stringLiteralValueCache.put(id, value)
                            stringLiteralIdCache.put(value, id)
                            returnMap[STRING_LITERAL_TYPE to id] = StringValue(value)
                        }

                    }

                }

                type < VECTOR_ID_OFFSET -> {
                    val pairSet = groupedPairs.toMutableSet()
                    //TODO batch

                    pairSet.forEach {
                        val value = getVectorQuadValue(it.first, it.second)
                        if (value != null) {
                            returnMap[it] = value
                        }
                    }

                }

                type == LOCAL_URI_TYPE -> {

                    val suffixIdSet = groupedPairs.map { it.second }.toMutableSet()

                    suffixIdSet.removeIf {
                        val cached = suffixValueCache.getIfPresent(it)
                        if (cached != null) {
                            returnMap[type to it] = LocalQuadValue(cached)
                            true
                        } else {
                            false
                        }
                    }

                    if (suffixIdSet.isNotEmpty()) {
                        val result = client.query(
                            Query("megras.entity").select("*").where(
                                Expression("id", "in", suffixIdSet)
                            )
                        )

                        while (result.hasNext()) {
                            val tuple = result.next()
                            val value = tuple.asString("value") ?: continue
                            val id = tuple.asLong("id") ?: continue
                            suffixIdCache.put(value, id)
                            suffixValueCache.put(id, value)
                            returnMap[type to id] = LocalQuadValue(value)
                        }
                    }

                }

                else -> { //URI Values

                    var prefix = prefixValueCache.getIfPresent(type)
                    if (prefix == null) {
                        val result = client.query(
                            Query("megras.entity_prefix").select("prefix").where(
                                Expression("id", "=", type)
                            )
                        )

                        if (result.hasNext()) {
                            val tuple = result.next()
                            val p = tuple.asString("prefix")
                            if (p != null) {
                                prefixValueCache.put(type, p)
                                prefixIdCache.put(p, type)
                            }
                            prefix = p
                        }

                    }

                    if (prefix == null) {
                        return@forEach
                    }

                    val suffixIdSet = groupedPairs.map { it.second }.toMutableSet()

                    suffixIdSet.removeIf {
                        val cached = suffixValueCache.getIfPresent(it)
                        if (cached != null) {
                            returnMap[type to it] = URIValue(prefix, cached)
                            true
                        } else {
                            false
                        }
                    }

                    if (suffixIdSet.isNotEmpty()) {
                        val result = client.query(
                            Query("megras.entity").select("*").where(
                                Expression("id", "in", suffixIdSet)
                            )
                        )

                        while (result.hasNext()) {
                            val tuple = result.next()
                            val value = tuple.asString("value") ?: continue
                            val id = tuple.asLong("id") ?: continue
                            suffixIdCache.put(value, id)
                            suffixValueCache.put(id, value)
                            returnMap[type to id] = URIValue(prefix, value)
                        }
                    }
                }
            }

        }


        return returnMap

    }

    override fun getDoubleValue(id: Long): DoubleValue? {

        val cached = doubleLiteralValueCache.getIfPresent(id)

        if (cached != null) {
            return DoubleValue(cached)
        }

        val result = client.query(
            Query("megras.literal_double")
                .select("value")
                .where(Expression("id", "=", id))
        )

        if (result.hasNext()) {
            val value = result.next().asDouble("value")
            if (value != null) {
                doubleLiteralIdCache.put(value, id)
                doubleLiteralValueCache.put(id, value)
                return DoubleValue(value)
            }
        }

        return null
    }

    override fun getStringValue(id: Long): StringValue? {

        val cached = stringLiteralValueCache.getIfPresent(id)

        if (cached != null) {
            return StringValue(cached)
        }

        val result = client.query(
            Query("megras.literal_string")
                .select("value")
                .where(Expression("id", "=", id))
        )

        if (result.hasNext()) {
            val value = result.next().asString("value")
            if (value != null) {
                stringLiteralValueCache.put(id, value)
                stringLiteralIdCache.put(value, id)
                return StringValue(value)
            }
        }

        return null
    }

    override fun getUriValue(type: Int, id: Long): URIValue? {

        fun prefix(id: Int): String? {

            val cached = prefixValueCache.getIfPresent(id)

            if (cached != null) {
                return cached
            }

            val result = client.query(
                Query("megras.entity_prefix").select("prefix").where(
                    Expression("id", "=", id)
                )
            )

            if (result.hasNext()) {
                val tuple = result.next()
                val prefix = tuple.asString("prefix")
                if (prefix != null) {
                    prefixValueCache.put(id, prefix)
                    prefixIdCache.put(prefix, id)
                }
                return prefix
            }

            return null
        }

        fun suffix(id: Long): String? {

            val cached = suffixValueCache.getIfPresent(id)

            if (cached != null) {
                return cached
            }

            val result = client.query(
                Query("megras.entity").select("value").where(
                    Expression("id", "=", id)
                )
            )

            if (result.hasNext()) {
                val tuple = result.next()
                val value = tuple.asString("value")
                if (value != null) {
                    suffixIdCache.put(value, id)
                    suffixValueCache.put(id, value)
                }
                return value
            }

            return null
        }

        if (type == LOCAL_URI_TYPE) {
            val suffix = suffix(id) ?: return null
            return LocalQuadValue(suffix)
        }

        val key = type to id
        val cached = uriValueIdCache.getIfPresent(key)

        if (cached != null) {
            return cached
        }

        val prefix = prefix(type) ?: return null
        val suffix = suffix(id) ?: return null

        val value = URIValue(prefix, suffix)

        uriValueValueCache.put(value, key)
        uriValueIdCache.put(key, value)

        return value
    }

    override fun getVectorQuadValue(type: Int, id: Long): VectorValue? {

        val pair = type to id

        val cached = vectorValueIdCache.getIfPresent(pair)
        if (cached != null) {
            return cached
        }

        val internalId = -type + VECTOR_ID_OFFSET

        val properties = getVectorProperties(internalId) ?: return null

        val name = "megras.vector_values_${internalId}"

        val result = client.query(Query(name).select("value").where(Expression("id", "=", id)))

        if (result.hasNext()) {
            val tuple = result.next()
            val value = when (properties.second) {
                VectorValue.Type.Double -> DoubleVectorValue(tuple.asDoubleVector("value")!!)
                VectorValue.Type.Long -> LongVectorValue(tuple.asLongVector("value")!!)
            }
            vectorValueValueCache.put(value, pair)
            vectorValueIdCache.put(pair, value)
            return value
        }

        return null
    }

    override fun getDoubleLiteralId(value: Double): Long? {

        val cached = doubleLiteralIdCache.getIfPresent(value)

        if (cached != null) {
            return cached
        }

        val result = client.query(
            Query("megras.literal_double").select("id").where(
                Expression("value", "=", value)
            )
        )

        if (result.hasNext()) {
            val tuple = result.next()
            val id = tuple.asLong("id")
            if (id != null) {
                doubleLiteralValueCache.put(id, value)
                doubleLiteralIdCache.put(value, id)
            }
            return id
        }

        return null
    }

    /**
     * Retrieves id of existing double literal or creates new one
     */
    override fun getOrAddDoubleLiteralId(value: Double): Long {

        val id = getDoubleLiteralId(value)

        if (id != null) {
            return id
        }

        //value not yet present, inserting new
        val result = client.insert(
            Insert("megras.literal_double").value("value", value)
        )

        if (result.hasNext()) {
            val id = result.next().asLong("id")
            if (id != null) {
                doubleLiteralValueCache.put(id, value)
                doubleLiteralIdCache.put(value, id)
                return id
            }
        }

        return getDoubleLiteralId(value) ?: throw IllegalStateException("could not obtain id for inserted value")

    }

    override fun getStringLiteralId(value: String): Long? {

        val cached = stringLiteralIdCache.getIfPresent(value)

        if (cached != null) {
            return cached
        }

        val result = client.query(
            Query("megras.literal_string").select("id").where(
                Expression("value", "=", value)
            )
        )

        if (result.hasNext()) {
            val tuple = result.next()
            val id = tuple.asLong("id")
            if (id != null) {
                stringLiteralValueCache.put(id, value)
                stringLiteralIdCache.put(value, id)
            }
            return id
        }

        return null
    }

    private fun getVectorEntity(type: VectorValue.Type, length: Int): Int? {
        val pair = length to type
        val cached = vectorEntityCache.getIfPresent(pair)
        if (cached != null) {
            return cached
        }

        val result = client.query(
            Query("megras.vector_types")
                .select("id")
                .where(
                    And(
                        Expression("length", "=", length),
                        Expression("type", "=", type.byte.toInt())
                    )
                )
        )

        if (result.hasNext()) {
            val tuple = result.next()
            val id = tuple.asInt("id")
            if (id != null) {
                vectorEntityCache.put(pair, id)
                vectorPropertyCache.put(id, pair)
            }
            return id
        }

        return null
    }

    private fun getVectorProperties(type: Int): Pair<Int, VectorValue.Type>? {

        val cached = vectorPropertyCache.getIfPresent(type)
        if (cached != null) {
            return cached
        }

        val result = client.query(
            Query("megras.vector_types")
                .select("*")
                .where(
                    Expression("id", "=", type)
                )
        )

        if (result.hasNext()) {
            val tuple = result.next()
            val pair = tuple.asInt("length")!! to VectorValue.Type.fromByte(tuple.asByte("type")!!)
            vectorEntityCache.put(pair, type)
            vectorPropertyCache.put(type, pair)
            return pair
        }

        return null

    }

    private fun getOrCreateVectorEntity(type: VectorValue.Type, length: Int): Int {


        fun createEntity(): Int {

            val result = client.insert(
                Insert("megras.vector_types").values("length" to length, "type" to type.byte.toInt())
            )


            val id = if (result.hasNext()) {
                result.next().asInt("id")!!
            } else {
                getVectorEntity(type, length)!!
            }

            val name = "megras.vector_values_${id}"

            client.create(
                CreateEntity(name)
                    .column("id", Type.LONG, autoIncrement = true)
                    .column("value", type.cottontailType(), length = length)

            )

            client.create(CreateIndex(name, "id", CottontailGrpc.IndexType.BTREE_UQ))

            return id

        }

        return getVectorEntity(type, length) ?: createEntity()

    }

    override fun getVectorQuadValueId(value: VectorValue): Pair<Int?, Long?> {

        val entityId = getVectorEntity(value.type, value.length) ?: return null to null

        val name = "megras.vector_values_${entityId}"

        val result = client.query(
            Query(name)
                .select("id")
                .where(
                    Expression(
                        "value", "=",
                        when (value.type) {
                            VectorValue.Type.Double -> (value as DoubleVectorValue).vector
                            VectorValue.Type.Long -> (value as LongVectorValue).vector
                        }
                    )
                )
        )

        val id = if (result.hasNext()) {
            val tuple = result.next()
            tuple.asLong("id")
        } else {
            return null to null
        } ?: return null to null

        return (-entityId + VECTOR_ID_OFFSET) to id
    }

    override fun getOrAddVectorQuadValueId(value: VectorValue): QuadValueId {

        val present = getVectorQuadValueId(value)

        if (present.first != null && present.second != null) {
            return present.first!! to present.second!!
        }

        val entityId = getOrCreateVectorEntity(value.type, value.length)

        val name = "megras.vector_values_${entityId}"

        val insertResult = client.insert(
            Insert(name).value(
                "value",
                when (value.type) {
                    VectorValue.Type.Double -> (value as DoubleVectorValue).vector
                    VectorValue.Type.Long -> (value as LongVectorValue).vector
                }
            )
        )

        if (insertResult.hasNext()) {
            val id = insertResult.next().asLong("id")
            if (id != null) {
                return (-entityId + VECTOR_ID_OFFSET) to id
            }
        }

        val result = client.query(
            Query(name)
                .select("id")
                .where(
                    Expression(
                        "value", "=",
                        when (value.type) {
                            VectorValue.Type.Double -> (value as DoubleVectorValue).vector
                            VectorValue.Type.Long -> (value as LongVectorValue).vector
                        }
                    )
                )
        )

        if (result.hasNext()) {
            val tuple = result.next()
            val id = tuple.asLong("id")
            if (id != null) {
                return (-entityId + VECTOR_ID_OFFSET) to id
            }
        }

        throw IllegalStateException("could not obtain id for inserted value")
    }

    /**
     * Retrieves id of existing string literal or creates new one
     */
    override fun getOrAddStringLiteralId(value: String): Long {

        val id = getStringLiteralId(value)

        if (id != null) {
            return id
        }

        //value not yet present, inserting new
        val result = client.insert(
            Insert("megras.literal_string").value("value", value)
        )

        if (result.hasNext()) {
            val id = result.next().asLong("id")
            if (id != null) {
                return id
            }
        }

        return getStringLiteralId(value) ?: throw IllegalStateException("could not obtain id for inserted value")

    }

    override fun getUriValueId(value: URIValue): Pair<Int?, Long?> {

        fun prefix(value: String): Int? {

            val cached = prefixIdCache.getIfPresent(value)
            if (cached != null) {
                return cached
            }

            val result = client.query(
                Query("megras.entity_prefix").select("id").where(
                    Expression("prefix", "=", value)
                )
            )

            if (result.hasNext()) {
                val tuple = result.next()
                val id = tuple.asInt("id")
                if (id != null) {
                    prefixValueCache.put(id, value)
                    prefixIdCache.put(value, id)
                }
                return id
            }

            return null
        }

        fun suffix(value: String): Long? {

            val cached = suffixIdCache.getIfPresent(value)

            if (cached != null) {
                return cached
            }

            val result = client.query(
                Query("megras.entity").select("id").where(
                    Expression("value", "=", value)
                )
            )

            if (result.hasNext()) {
                val tuple = result.next()
                val id = tuple.asLong("id")
                if (id != null) {
                    suffixIdCache.put(value, id)
                    suffixValueCache.put(id, value)
                }
                return id
            }

            return null
        }

        if (value is LocalQuadValue || value.prefix() == LocalQuadValue.defaultPrefix) {
            return LOCAL_URI_TYPE to suffix(value.suffix())
        }

        val cached = uriValueValueCache.getIfPresent(value)
        if (cached != null) {
            return cached
        }

        val pair = prefix(value.prefix()) to suffix(value.suffix())

        if (pair.first != null && pair.second != null) {

            val key = pair.first!! to pair.second!!
            uriValueIdCache.put(key, value)
            uriValueValueCache.put(value, key)
            return key

        }

        return pair

    }

    override fun getOrAddUriValueId(value: URIValue): QuadValueId {

        var (prefix, suffix) = getUriValueId(value)

        if (prefix == null) {
            val result = client.insert(
                Insert("megras.entity_prefix").value("prefix", value.prefix())
            )
            if (result.hasNext()) {
                prefix = result.next().asInt("id")
            }
        }

        if (suffix == null) {
            val result = client.insert(
                Insert("megras.entity").value("value", value.suffix())
            )
            if (result.hasNext()) {
                suffix = result.next().asLong("id")
            }
        }

        if (prefix != null && suffix != null) {
            return prefix to suffix
        }

        val pair = getUriValueId(value)

        if (pair.first == null || pair.second == null) {
            throw IllegalStateException("could not obtain id for inserted value")
        }

        return pair.first!! to pair.second!!

    }

    private fun filterExpression(column: String, type: Int, id: Long) = And(
        Expression("${column}_type", "=", type),
        Expression(column, "=", id)
    )

    private fun filterExpression(column: String, type: Int, ids: Collection<Long>) = And(
        Expression("${column}_type", "=", type),
        Expression(column, "in", ids)
    )

    private fun subjectFilterExpression(type: Int, id: Long) = filterExpression("s", type, id)
    private fun predicateFilterExpression(type: Int, id: Long) = filterExpression("p", type, id)
    private fun objectFilterExpression(type: Int, id: Long) = filterExpression("o", type, id)
    private fun objectFilterExpression(type: Int, ids: Collection<Long>) = filterExpression("o", type, ids)

    private fun getQuadId(subject: QuadValueId, predicate: QuadValueId, `object`: QuadValueId): Long? {
        val result = client.query(
            Query("megras.quads")
                .select("id")
                .where(
                    Expression(
                        "hash",
                        "=",
                        quadHash(
                            subject.first,
                            subject.second,
                            predicate.first,
                            predicate.second,
                            `object`.first,
                            `object`.second
                        )
                    )
                )
        )
        if (result.hasNext()) {
            return result.next().asLong("id")
        }
        return null
    }

    private fun insert(sType: Int, s: Long, pType: Int, p: Long, oType: Int, o: Long): Long {
        val result = client.insert(
            Insert("megras.quads")
                .value("s_type", sType)
                .value("s", s)
                .value("p_type", pType)
                .value("p", p)
                .value("o_type", oType)
                .value("o", o)
                .value("hash", quadHash(sType, s, pType, p, oType, o))
        )
        if (result.hasNext()) {
            val id = result.next().asLong("id")
            if (id != null) {
                return id
            }
        }
        throw IllegalStateException("could not obtain id for inserted value")
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

    /**
     * Stores a Quad if it doesn't already exist and returns its id
     */
    fun addQuad(quad: Quad): Long {

        val s = getOrAddQuadValueId(quad.subject)
        val p = getOrAddQuadValueId(quad.predicate)
        val o = getOrAddQuadValueId(quad.`object`)

        val existingId = getQuadId(s, p, o)

        if (existingId != null) {
            return existingId
        }

        return insert(s.first, s.second, p.first, p.second, o.first, o.second)

        //return getQuadId(s, p, o) ?: throw IllegalStateException("could not obtain id for inserted value")

    }

    override fun getId(id: Long): Quad? {

        val result = client.query(
            Query("megras.quads")
                .select("*")
                .where(Expression("id", "=", id))
        )

        if (!result.hasNext()) {
            return null
        }

        val tuple = result.next()

        val s = getQuadValue(tuple.asInt("s_type")!!, tuple.asLong("s")!!) ?: return null
        val p = getQuadValue(tuple.asInt("p_type")!!, tuple.asLong("p")!!) ?: return null
        val o = getQuadValue(tuple.asInt("o_type")!!, tuple.asLong("o")!!) ?: return null

        return Quad(id, s, p, o)
    }

    private fun getIds(ids: Collection<Long>): BasicQuadSet {

        val result = client.query(
            Query("megras.quads")
                .select("*")
                .where(Expression("id", "in", ids))
        )

        if (!result.hasNext()) {
            return BasicQuadSet()
        }

        val quads = mutableSetOf<Quad>()

        val valueIds = mutableSetOf<QuadValueId>()
        val quadIds =
            mutableListOf<Pair<Long, Triple<QuadValueId, QuadValueId, QuadValueId>>>()

        while (result.hasNext()) {
            val tuple = result.next()

            val id = tuple.asLong("id")!!

            val s = (tuple.asInt("s_type") ?: continue) to (tuple.asLong("s") ?: continue)
            val p = (tuple.asInt("p_type") ?: continue) to (tuple.asLong("p") ?: continue)
            val o = (tuple.asInt("o_type") ?: continue) to (tuple.asLong("o") ?: continue)

            valueIds.add(s)
            valueIds.add(p)
            valueIds.add(o)

            quadIds.add(id to Triple(s, p, o))
        }

        val values = getQuadValues(valueIds)

        quadIds.forEach {
            val s = values[it.second.first] ?: return@forEach
            val p = values[it.second.second] ?: return@forEach
            val o = values[it.second.third] ?: return@forEach

            quads.add(Quad(it.first, s, p, o))
        }

        return BasicQuadSet(quads)

    }


    override fun filterSubject(subject: QuadValue): QuadSet {

        val s = getQuadValueId(subject)

        if (s.first == null || s.second == null) { //no match, no results
            return BasicQuadSet() //return empty set
        }

        val result = client.query(
            Query("megras.quads")
                .select("id")
                .where(
                    subjectFilterExpression(s.first!!, s.second!!)
                )
        )

        val quadSet = BasicMutableQuadSet()
        while (result.hasNext()) {
            val id = result.next().asLong("id") ?: continue
            val quad = getId(id) ?: continue
            quadSet.add(quad)
        }

        return quadSet
    }

    override fun filterPredicate(predicate: QuadValue): QuadSet {
        val p = getQuadValueId(predicate)

        if (p.first == null || p.second == null) { //no match, no results
            return BasicQuadSet() //return empty set
        }

        val result = client.query(
            Query("megras.quads")
                .select("id")
                .where(
                    predicateFilterExpression(p.first!!, p.second!!)
                )
        )

        val quadSet = BasicMutableQuadSet()
        while (result.hasNext()) {
            val id = result.next().asLong("id") ?: continue
            val quad = getId(id) ?: continue
            quadSet.add(quad)
        }

        return quadSet
    }

    override fun filterObject(`object`: QuadValue): QuadSet {
        val o = getQuadValueId(`object`)

        if (o.first == null || o.second == null) { //no match, no results
            return BasicQuadSet() //return empty set
        }

        val result = client.query(
            Query("megras.quads")
                .select("id")
                .where(
                    objectFilterExpression(o.first!!, o.second!!)
                )
        )

        val quadSet = BasicMutableQuadSet()
        while (result.hasNext()) {
            val id = result.next().asLong("id") ?: continue
            val quad = getId(id) ?: continue
            quadSet.add(quad)
        }

        return quadSet
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

        val filterIds = getQuadValueIds(
            (subjects?.toSet() ?: setOf()) + (predicates?.toSet() ?: setOf()) + (objects?.toSet() ?: setOf())
        )

        val subjectFilterIds = subjects?.mapNotNull { filterIds[it] }
        val predicateFilterIds = predicates?.mapNotNull { filterIds[it] }
        val objectFilterIds = objects?.mapNotNull { filterIds[it] }

        //no matching values
        if (subjectFilterIds?.isEmpty() == true || predicateFilterIds?.isEmpty() == true || objectFilterIds?.isEmpty() == true) {
            return BasicQuadSet()
        }

        fun select(predicates: Collection<Predicate>): Set<Long> {
            if (predicates.isEmpty()) {
                return emptySet()
            }

            val predicate = predicates.reduce { acc, predicate -> Or(acc, predicate) }

            val result = client.query(
                Query("megras.quads")
                    .select("id")
                    .where(predicate)
            )

            val ids = mutableSetOf<Long>()

            while (result.hasNext()) {
                val id = result.next().asLong("id") ?: continue
                ids.add(id)
            }

            return ids

        }

        fun predicate(column: String, ids: Collection<QuadValueId>?) = ids?.groupBy { it.first }
            ?.map { (type, ids) ->
                And(
                    Expression("${column}_type", "=", type),
                    Expression(column, "in", ids.map { it.second })
                ) as Predicate
            }?.reduce { acc, pred -> Or(acc, pred) }

        val ids = select(
            listOf(listOfNotNull(
                predicate("s", subjectFilterIds),
                predicate("p", predicateFilterIds),
                predicate("o", objectFilterIds)
            ).reduce { acc, predicate -> And(acc, predicate) }
            )
        )

        return getIds(ids).toMutable()
    }

    override fun toMutable(): MutableQuadSet = this

    override fun toSet(): Set<Quad> {
        TODO("Not yet implemented")
    }

    override fun plus(other: QuadSet): QuadSet {
        TODO("Not yet implemented")
    }

    override fun nearestNeighbor(predicate: QuadValue, `object`: VectorValue, count: Int, distance: Distance): QuadSet {

        val predId = getQuadValueId(predicate)

        if (predId.first == null || predId.second == null) {
            return BasicQuadSet()
        }

        val vectorEntity = getVectorEntity(`object`.type, `object`.length) ?: return BasicQuadSet()
        val vectorId = -vectorEntity + VECTOR_ID_OFFSET
//
//        val result = client.query(
//            Query("megras.quads")
//                .select("id")
//                .select("o")
//                .where(
//                    And(
//                        predicateFilterExpression(predId.first!!, predId.second!!),
//                        Expression("o_type", "=", vectorId)
//                    )
//                )
//        )
//
//        if (!result.hasNext()) {
//            return BasicQuadSet()
//        }
//
//        val objectIds = mutableSetOf<Long>()

        val quadIds = mutableListOf<Pair<Long, Long>>() //quad id to object id

//        while (result.hasNext()) {
//            val tuple = result.next()
//            val o = tuple.asLong("o")!!
//            objectIds.add(o)
//            quadIds.add(tuple.asLong("id")!! to o)
//        }


        val knnResult = client.query(
            Query("megras.vector_values_${vectorEntity}")
                .select("id")
//                .where(Expression("id", "in", objectIds))
                .distance(
                    "value",
                    when (`object`) {
                        is DoubleVectorValue -> `object`.vector
                        is LongVectorValue -> `object`.vector
                        else -> error("unknown vector value type")
                    }, distance.cottontail(), "distance"
                )
                .limit(count.toLong())
                .order("distance", Direction.ASC)

        )

        if (!knnResult.hasNext()) {
            return BasicQuadSet()
        }

        val distances = mutableMapOf<Long, Double>()

        while (knnResult.hasNext()) {
            val tuple = knnResult.next()
            distances[tuple.asLong("id")!!] = tuple.asDouble("distance")!!
        }


        val result = client.query(
            Query("megras.quads")
                .select("id")
                .select("o")
                .where(
                    And(
                        And(
                            predicateFilterExpression(predId.first!!, predId.second!!),
                            Expression("o_type", "=", vectorId)
                        ),
                        Expression("o", "in", distances.keys)
                    )
                )
        )

        while (result.hasNext()) {
            val tuple = result.next()
            val o = tuple.asLong("o")!!
            quadIds.add(tuple.asLong("id")!! to o)
        }

        val relevantQuadIds =
            distances.keys.flatMap { oid -> quadIds.filter { it.second == oid } }.map { it.first }.toSet()
        val relevantQuads = getIds(relevantQuadIds)

        val ret = BasicMutableQuadSet()
        //ret.addAll(relevantQuads)

        val quadIdMap = quadIds.toMap()

        relevantQuads.forEach { quad ->
            val d = distances[quadIdMap[quad.id!!]!!] ?: return@forEach
            ret.add(Quad(quad.subject, MeGraS.QUERY_DISTANCE.uri, DoubleValue(d)))
        }

        return ret
    }

    override fun textFilter(predicate: QuadValue, objectFilterText: String): QuadSet {

        val predicatePair = getQuadValueId(predicate)

        if (predicatePair.first == null || predicatePair.second == null) { //unknown predicate, can't have matching quads
            return BasicQuadSet()
        }

        val filterText = if (objectFilterText.startsWith('"') && objectFilterText.endsWith('"')) {
            objectFilterText
        } else {
            "\"${objectFilterText.replace("\"", "\\\"")}\""
        }

        val ids = client.query(
            Query("megras.literal_string")
                .select("id")
                .fulltext("value", filterText, "score")
        )

        val idList = mutableListOf<Long>()
        while (ids.hasNext()) {
            idList.add(
                ids.next().asLong("id")!!
            )
        }

        val resultIds = mutableSetOf<Long>()
        val result = client.query(
            Query("megras.quads")
                .select("id")
                .where(
                    And(
                        objectFilterExpression(STRING_LITERAL_TYPE, idList),
                        predicateFilterExpression(predicatePair.first!!, predicatePair.second!!)
                    )
                )
        )

        while (result.hasNext()) {

            resultIds.add(result.next().asLong("id") ?: continue)

        }

        return getIds(resultIds)
    }

    override val size: Int
        get() = 0 //TODO

    private fun getQuadId(quad: Quad): Long? {
        val s = getQuadValueId(quad.subject)

        if (s.first == null || s.second == null) {
            return null
        }

        val p = getQuadValueId(quad.predicate)

        if (p.first == null || p.second == null) {
            return null
        }

        val o = getQuadValueId(quad.`object`)

        if (o.first == null || o.second == null) {
            return null
        }

        return getQuadId(s.first!! to s.second!!, p.first!! to p.second!!, o.first!! to o.second!!)
    }

    override fun contains(element: Quad): Boolean = getQuadId(element) != null

    override fun containsAll(elements: Collection<Quad>): Boolean {
        return elements.all { contains(it) }
    }

    override fun isEmpty(): Boolean = this.size == 0

    override fun iterator(): MutableIterator<Quad> {
        TODO("Not yet implemented")
    }

    override fun add(element: Quad): Boolean {

        val s = getOrAddQuadValueId(element.subject)
        val p = getOrAddQuadValueId(element.predicate)
        val o = getOrAddQuadValueId(element.`object`)

        val existingId = getQuadId(s, p, o)

        if (existingId != null) {
            return false
        }

        insert(s.first, s.second, p.first, p.second, o.first, o.second)

        return true
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

        val result = client.query(
            Query("megras.quads")
                .select("hash")
                .where(Expression("hash", "in", quadIdMap.keys))
        )

        while (result.hasNext()) {
            val tuple = result.next()
            val hash = tuple.asString("hash") ?: continue
            quadIdMap.remove(hash)
        }

        if (quadIdMap.isEmpty()) {
            return false
        }

        val batchInsert = BatchInsert("megras.quads").columns("s_type", "s", "p_type", "p", "o_type", "o", "hash")

        quadIdMap.forEach {
            val s = valueIdMap[it.value.subject]!!
            val p = valueIdMap[it.value.predicate]!!
            val o = valueIdMap[it.value.`object`]!!
            batchInsert.append(s.first, s.second, p.first, p.second, o.first, o.second, it.key)
        }

        client.insert(batchInsert)

        return true
    }

    override fun clear() {
        TODO("Not yet implemented")
    }

    override fun remove(element: Quad): Boolean {

        fun delete(quadId: Long) {
            client.delete(
                Delete("megras.quads").where(Expression("id", "=", quadId))
            )
        }

        if (element.id != null) {
            val storedQuad = getId(element.id)
            if (storedQuad == element) {
                delete(element.id)
                return true
            }
        } else {
            val id = getQuadId(element) ?: return false
            delete(id)
            return true
        }
        return false
    }

    override fun removeAll(elements: Collection<Quad>): Boolean {
        return elements.map { remove(it) }.any()
    }

    override fun retainAll(elements: Collection<Quad>): Boolean {
        TODO("Not yet implemented")
    }

}