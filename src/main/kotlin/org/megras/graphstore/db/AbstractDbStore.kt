package org.megras.graphstore.db

import com.google.common.cache.CacheBuilder
import org.megras.data.graph.*
import org.megras.graphstore.MutableQuadSet

abstract class AbstractDbStore : MutableQuadSet {

    companion object {
        const val QUAD_TYPE = 0
        const val LOCAL_URI_TYPE = -1
        const val LONG_LITERAL_TYPE = -2
        const val DOUBLE_LITERAL_TYPE = -3
        const val STRING_LITERAL_TYPE = -4

        const val BINARY_DATA_TYPE = -9
        const val VECTOR_ID_OFFSET = -10
    }

    private val cacheSize = 10000L

    val stringLiteralIdCache = CacheBuilder.newBuilder().maximumSize(cacheSize).build<String, Long>()
    val stringLiteralValueCache = CacheBuilder.newBuilder().maximumSize(cacheSize).build<Long, String>()

    val doubleLiteralIdCache = CacheBuilder.newBuilder().maximumSize(cacheSize).build<Double, Long>()
    val doubleLiteralValueCache = CacheBuilder.newBuilder().maximumSize(cacheSize).build<Long, Double>()

    val prefixValueCache = CacheBuilder.newBuilder().maximumSize(cacheSize).build<Int, String>()
    val prefixIdCache = CacheBuilder.newBuilder().maximumSize(cacheSize).build<String, Int>()

    val suffixValueCache = CacheBuilder.newBuilder().maximumSize(cacheSize).build<Long, String>()
    val suffixIdCache = CacheBuilder.newBuilder().maximumSize(cacheSize).build<String, Long>()

    val uriValueIdCache = CacheBuilder.newBuilder().maximumSize(cacheSize).build<QuadValueId, URIValue>()
    val uriValueValueCache = CacheBuilder.newBuilder().maximumSize(cacheSize).build<URIValue, QuadValueId>()

    val vectorEntityCache =
        CacheBuilder.newBuilder().maximumSize(cacheSize).build<Pair<Int, VectorValue.Type>, Int>()
    val vectorPropertyCache =
        CacheBuilder.newBuilder().maximumSize(cacheSize).build<Int, Pair<Int, VectorValue.Type>>()

    val vectorValueIdCache =
        CacheBuilder.newBuilder().maximumSize(cacheSize).build<QuadValueId, VectorValue>()
    val vectorValueValueCache =
        CacheBuilder.newBuilder().maximumSize(cacheSize).build<VectorValue, QuadValueId>()


    abstract fun setup()

    fun getQuadValueId(quadValue: QuadValue): Pair<Int?, Long?> {

        return when (quadValue) {
            is DoubleValue -> DOUBLE_LITERAL_TYPE to getDoubleLiteralId(quadValue.value)
            is LongValue -> LONG_LITERAL_TYPE to quadValue.value //no indirection needed
            is StringValue -> STRING_LITERAL_TYPE to getStringLiteralId(quadValue.value)
            is URIValue -> getUriValueId(quadValue)
            is VectorValue -> getVectorQuadValueId(quadValue)
        }

    }

    abstract fun getVectorQuadValueId(quadValue: VectorValue): Pair<Int?, Long?>

    abstract fun getUriValueId(quadValue: URIValue): Pair<Int?, Long?>

    abstract fun getStringLiteralId(value: String): Long?

    abstract fun getDoubleLiteralId(value: Double): Long?

    fun getOrAddQuadValueId(quadValue: QuadValue): QuadValueId {

        return when (quadValue) {
            is DoubleValue -> DOUBLE_LITERAL_TYPE to getOrAddDoubleLiteralId(quadValue.value)
            is LongValue -> LONG_LITERAL_TYPE to quadValue.value //no indirection needed
            is StringValue -> STRING_LITERAL_TYPE to getOrAddStringLiteralId(quadValue.value)
            is URIValue -> getOrAddUriValueId(quadValue)
            is VectorValue -> getOrAddVectorQuadValueId(quadValue)
        }

    }

    abstract fun getOrAddVectorQuadValueId(quadValue: VectorValue): Pair<Int, Long>

    abstract fun getOrAddUriValueId(quadValue: URIValue): Pair<Int, Long>

    abstract fun getOrAddStringLiteralId(value: String): Long

    abstract fun getOrAddDoubleLiteralId(value: Double): Long

    fun getQuadValue(type: Int, id: Long): QuadValue? {

        return when {
            type == DOUBLE_LITERAL_TYPE -> getDoubleValue(id)
            type == LONG_LITERAL_TYPE -> LongValue(id)
            type == STRING_LITERAL_TYPE -> getStringValue(id)
            type < VECTOR_ID_OFFSET -> getVectorQuadValue(type, id)
            else -> getUriValue(type, id)
        }

    }

    abstract fun getUriValue(type: Int, id: Long): QuadValue?

    abstract fun getVectorQuadValue(type: Int, id: Long): QuadValue?

    abstract fun getStringValue(id: Long): QuadValue?

    abstract fun getDoubleValue(id: Long): QuadValue?

    fun getQuadValueIds(quadValues: Collection<QuadValue>): Map<QuadValue, QuadValueId> {

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
            val map = lookUpDoubleValueIds(doubleValues)

            map.forEach { (value, id) ->
                doubleLiteralValueCache.put(id.second, value.value)
                doubleLiteralIdCache.put(value.value, id.second)
            }

            returnMap.putAll(map)

        }

        if (stringValues.isNotEmpty()) {

            val map = lookUpStringValueIds(stringValues)

            map.forEach { (value, id) ->
                stringLiteralValueCache.put(id.second, value.value)
                stringLiteralIdCache.put(value.value, id.second)
            }

            returnMap.putAll(map)

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

                val map = lookUpPrefixIds(prefixValues)

                map.forEach { (value, id) ->
                    prefixValueCache.put(id, value)
                    prefixIdCache.put(value, id)
                }

                prefixIdMap.putAll(map)

            }

            if (suffixValues.isNotEmpty()) {

                val map = lookUpSuffixIds(suffixValues)

                map.forEach { (value, id) ->
                    suffixIdCache.put(value, id)
                    suffixValueCache.put(id, value)
                }

                suffixIdMap.putAll(map)

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

            val map = lookUpVectorValueIds(vectorValues)

            map.forEach { (value, id) ->
                vectorValueValueCache.put(value, id)
                vectorValueIdCache.put(id, value)
            }

            returnMap.putAll(map)

        }

        return returnMap

    }

    abstract fun lookUpDoubleValueIds(doubleValues: Set<DoubleValue>): Map<DoubleValue, QuadValueId>
    abstract fun lookUpStringValueIds(stringValues: Set<StringValue>): Map<StringValue, QuadValueId>
    abstract fun lookUpPrefixIds(prefixValues: Set<String>): Map<String, Int>
    abstract fun lookUpSuffixIds(suffixValues: Set<String>): Map<String, Long>
    abstract fun lookUpVectorValueIds(vectorValues: Set<VectorValue>): Map<VectorValue, QuadValueId>


}