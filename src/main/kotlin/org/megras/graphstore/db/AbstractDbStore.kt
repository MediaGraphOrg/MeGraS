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

}