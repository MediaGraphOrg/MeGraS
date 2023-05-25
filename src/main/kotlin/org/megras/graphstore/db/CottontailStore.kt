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

    val vectorEntityCache =
        CacheBuilder.newBuilder().maximumSize(cacheSize).build<Pair<Int, VectorValue.Type>, Int>()
    val vectorPropertyCache =
        CacheBuilder.newBuilder().maximumSize(cacheSize).build<Int, Pair<Int, VectorValue.Type>>()


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

    override fun lookUpDoubleValueIds(doubleValues: Set<DoubleValue>): Map<DoubleValue, QuadValueId> {
        if (doubleValues.isEmpty()) {
            return emptyMap()
        }

        val result = client.query(
            Query("megras.literal_double").select("*").where(
                Expression("value", "in", doubleValues.map { it.value })
            )
        )

        val returnMap = HashMap<DoubleValue, QuadValueId>(doubleValues.size)

        while (result.hasNext()) {
            val tuple = result.next()
            val id = tuple.asLong("id") ?: continue
            val value = tuple.asDouble("value") ?: continue
            val v = DoubleValue(value)
            returnMap[v] = DOUBLE_LITERAL_TYPE to id
        }

        return returnMap

    }

    override fun lookUpStringValueIds(stringValues: Set<StringValue>): Map<StringValue, QuadValueId> {

        if (stringValues.isEmpty()) {
            return emptyMap()
        }

        val result = client.query(
            Query("megras.literal_string")
                .select("*")
                .where(Expression("value", "in", stringValues.map { it.value }))
        )

        val returnMap = HashMap<StringValue, QuadValueId>(stringValues.size)

        while (result.hasNext()) {
            val tuple = result.next()
            val id = tuple.asLong("id") ?: continue
            val value = tuple.asString("value") ?: continue
            val v = StringValue(value)
            returnMap[v] = STRING_LITERAL_TYPE to id
        }

        return returnMap
    }

    override fun lookUpPrefixIds(prefixValues: Set<String>): Map<String, Int> {

        if (prefixValues.isEmpty()) {
            return emptyMap()
        }

        val result = client.query(
            Query("megras.entity_prefix").select("*").where(
                Expression("prefix", "in", prefixValues)
            )
        )

        val prefixIdMap = HashMap<String, Int>(prefixValues.size)

        while (result.hasNext()) {
            val tuple = result.next()
            val id = tuple.asInt("id") ?: continue
            val value = tuple.asString("prefix") ?: continue
            prefixIdMap[value] = id
        }

        return prefixIdMap

    }

    override fun lookUpSuffixIds(suffixValues: Set<String>): Map<String, Long> {

        if (suffixValues.isEmpty()) {
            return emptyMap()
        }

        val result = client.query(
            Query("megras.entity").select("*").where(
                Expression("value", "in", suffixValues)
            )
        )

        val suffixIdMap = HashMap<String, Long>(suffixValues.size)

        while (result.hasNext()) {
            val tuple = result.next()
            val id = tuple.asLong("id") ?: continue
            val value = tuple.asString("value") ?: continue
            suffixIdMap[value] = id
        }

        return suffixIdMap

    }

    override fun lookUpVectorValueIds(vectorValues: Set<VectorValue>): Map<VectorValue, QuadValueId> {

        if (vectorValues.isEmpty()) {
            return emptyMap()
        }

        val returnMap = HashMap<VectorValue, QuadValueId>(vectorValues.size)

        vectorValues.groupBy { it.type to it.length }.forEach { (properties, vectorList) ->

            val vectors = vectorList.toSet()

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
                returnMap[value] = pair
            }
        }

        return returnMap

    }

    override fun insertDoubleValues(doubleValues: Set<DoubleValue>): Map<DoubleValue, QuadValueId> {

        if (doubleValues.isEmpty()) {
            return emptyMap()
        }

        val batchInsert = BatchInsert("megras.literal_double").columns("value")
        doubleValues.forEach {
            require(batchInsert.append(it.value)) { "could not add value to batch, try reducing batch size" }
        }

        client.insert(batchInsert)

        return lookUpDoubleValueIds(doubleValues)

    }

    override fun insertStringValues(stringValues: Set<StringValue>): Map<StringValue, QuadValueId> {

        if (stringValues.isEmpty()) {
            return emptyMap()
        }

        val batchInsert = BatchInsert("megras.literal_string").columns("value")
        stringValues.forEach {
            require(batchInsert.append(it.value)) { "could not add value to batch, try reducing batch size" }
        }
        client.insert(batchInsert)

        return lookUpStringValueIds(stringValues)

    }

    override fun insertPrefixValues(prefixValues: Set<String>): Map<String, Int> {

        if (prefixValues.isEmpty()) {
            return emptyMap()
        }

        val batchInsert = BatchInsert("megras.entity_prefix").columns("prefix")
        prefixValues.forEach { value ->
            require(batchInsert.append(value)) { "could not add value to batch, try reducing batch size" }
        }
        client.insert(batchInsert)

        return lookUpPrefixIds(prefixValues)

    }

    override fun insertSuffixValues(suffixValues: Set<String>): Map<String, Long> {

        if (suffixValues.isEmpty()) {
            return emptyMap()
        }

        val batchInsert = BatchInsert("megras.entity").columns("value")
        suffixValues.forEach { value ->
            require(batchInsert.append(value)) { "could not add value to batch, try reducing batch size" }
        }

        client.insert(batchInsert)

        return lookUpSuffixIds(suffixValues)

    }

    override fun insertVectorValueIds(vectorValues: Set<VectorValue>): Map<VectorValue, QuadValueId> {

        if (vectorValues.isEmpty()) {
            return emptyMap()
        }

        vectorValues.groupBy { it.type to it.length }.forEach { (properties, v) ->

            val vectors = v.toMutableSet()

            val entityId = getOrCreateVectorEntity(properties.first, properties.second)
            val name = "megras.vector_values_${entityId}"

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
            }
        }

        return lookUpVectorValueIds(vectorValues)

    }


    override fun lookUpDoubleValues(ids: Set<Long>) : Map<QuadValueId, DoubleValue> {

        if (ids.isEmpty()) {
            return emptyMap()
        }

        val result = client.query(
            Query("megras.literal_double")
                .select("*")
                .where(Expression("id", "in", ids))
        )

        val returnMap = HashMap<QuadValueId, DoubleValue>(ids.size)

        while (result.hasNext()) {
            val tuple = result.next()
            val value = tuple.asDouble("value") ?: continue
            val id = tuple.asLong("id") ?: continue
            returnMap[DOUBLE_LITERAL_TYPE to id] = DoubleValue(value)
        }

        return returnMap

    }

    override fun lookUpStringValues(ids: Set<Long>) : Map<QuadValueId, StringValue> {

        if (ids.isEmpty()) {
            return emptyMap()
        }

        val result = client.query(
            Query("megras.literal_string")
                .select("*")
                .where(Expression("id", "in", ids))
        )

        val returnMap = HashMap<QuadValueId, StringValue>(ids.size)

        while (result.hasNext()) {
            val tuple = result.next()
            val value = tuple.asString("value") ?: continue
            val id = tuple.asLong("id") ?: continue
            returnMap[STRING_LITERAL_TYPE to id] = StringValue(value)
        }

        return returnMap

    }

    override fun lookUpVectorValues(ids: Set<QuadValueId>) : Map<QuadValueId, VectorValue> {

        if (ids.isEmpty()) {
            return emptyMap()
        }

        val returnMap = HashMap<QuadValueId, VectorValue>(ids.size)

        //TODO batch by type
        ids.forEach {
            val value = getVectorQuadValue(it.first, it.second)
            if (value != null) {
                returnMap[it] = value
            }
        }

        return returnMap

    }

    override fun lookUpPrefixes(ids: Set<Int>): Map<Int, String> {

        if (ids.isEmpty()) {
            return emptyMap()
        }

        val result = client.query(
            Query("megras.entity_prefix")
                .select("*")
                .where(Expression("id", "in", ids))
        )

        val map = HashMap<Int, String>(ids.size)

        while (result.hasNext()) {
            val tuple = result.next()
            val prefix = tuple.asString("prefix") ?: continue
            val id = tuple.asInt("id") ?: continue
            map[id] = prefix
        }

        return map
    }

    override fun lookUpSuffixes(ids: Set<Long>): Map<Long, String> {

        if (ids.isEmpty()) {
            return emptyMap()
        }

        val result = client.query(
            Query("megras.entity").select("*").where(
                Expression("id", "in", ids)
            )
        )

        val map = HashMap<Long, String>(ids.size)

        while (result.hasNext()) {
            val tuple = result.next()
            val value = tuple.asString("value") ?: continue
            val id = tuple.asLong("id") ?: continue
            map[id] = value
        }

        return map
    }




    private fun getVectorQuadValue(type: Int, id: Long): VectorValue? {

//        val pair = type to id
//
//        val cached = vectorValueIdCache.getIfPresent(pair)
//        if (cached != null) {
//            return cached
//        }

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
//            vectorValueValueCache.put(value, pair)
//            vectorValueIdCache.put(pair, value)
            return value
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

    override fun getQuadId(subject: QuadValueId, predicate: QuadValueId, `object`: QuadValueId): Long? {
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

    override fun insert(s: QuadValueId, p: QuadValueId, o: QuadValueId): Long = insert(s.first, s.second, p.first, p.second, o.first, o.second)
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





    override fun isEmpty(): Boolean = this.size == 0

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