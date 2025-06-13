package org.megras.data.graph

import java.io.Serializable
import java.net.URI
import java.net.URISyntaxException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

sealed class QuadValue : Serializable {

    companion object {

        fun of(value: Any) = when (value) {
            is Number -> {
                if (value is Float || value is Double) {
                    of(value.toDouble())
                } else {
                    of(value.toLong())
                }
            }

            is String -> of(value)
            is DoubleArray -> of(value)
            is LongArray -> of(value)
            is FloatArray -> of(value)
            else -> of(value.toString())
        }

        fun of(value: String) = when {
            value.startsWith('<') && value.endsWith('>') -> if(value.startsWith("<${LocalQuadValue.defaultPrefix}")) {
                LocalQuadValue(value.substringAfter(LocalQuadValue.defaultPrefix).substringBeforeLast('>'))
            } else {
                URIValue(value)
            }
            value.endsWith("^^String") -> StringValue(value.substringBeforeLast("^^String"))
            value.endsWith("^^Long") -> LongValue(value.substringBeforeLast("^^Long").toLongOrNull() ?: 0L)
            value.endsWith("^^Double") -> DoubleValue(value.substringBeforeLast("^^Double").toDoubleOrNull() ?: Double.NaN)
            value.startsWith("[") -> when { //vectors
                value.endsWith("]") || value.endsWith("]^^DoubleVector") -> {
                    DoubleVectorValue.parse(value)
                }
                value.endsWith("]^^LongVector") -> LongVectorValue.parse(value)
                value.endsWith("]^^FloatVector") -> FloatVectorValue.parse(value)
                else -> StringValue(value) //not a valid vector after all

            }
            else -> StringValue(value)
        }
        fun of(value: Long) = LongValue(value)
        fun of(value: Double) = DoubleValue(value)
        fun of(prefix: String, uri: String) = URIValue(prefix, uri)
        fun of(value: DoubleArray) = DoubleVectorValue(value)
        fun of(value: List<Double>) = DoubleVectorValue(value)
        fun of(value: LongArray) = LongVectorValue(value)
        fun of(value: List<Long>) = LongVectorValue(value)
        fun of(value: List<Float>) = FloatVectorValue(value)
        fun of(value: FloatArray) = FloatVectorValue(value)


    }

}

data class StringValue(val value: String): QuadValue(), Serializable {
    override fun toString(): String = "$value^^String"
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as StringValue

        return value == other.value
    }

    override fun hashCode(): Int {
        return value.hashCode()
    }
}
data class LongValue(val value: Long): QuadValue(), Serializable {
    override fun toString(): String = "$value^^Long"
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as LongValue

        return value == other.value
    }

    override fun hashCode(): Int {
        return value.hashCode()
    }
}
data class DoubleValue(val value: Double): QuadValue(), Serializable {
    override fun toString(): String = "$value^^Double"
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DoubleValue

        return value == other.value
    }

    override fun hashCode(): Int {
        return value.hashCode()
    }
}

open class URIValue(private val prefix: String?, protected open val uri: String) : QuadValue(), Serializable {

    companion object {
        private fun estimatePrefix(uri: String): Pair<String, String> {

            val trimmed = uri.trim()

            val cleaned = if (trimmed.startsWith('<') && trimmed.endsWith('>')) {
                trimmed.substring(1).substringBeforeLast('>')
            } else {
                trimmed
            }

            //best effort prefix estimator
            return try {

                if (cleaned.contains('#') && !cleaned.endsWith('#')) {
                    val suffix = cleaned.substringAfterLast('#')
                    return cleaned.substringBeforeLast(suffix) to suffix
                }

                val parsedUri = URI(cleaned)
                val host = parsedUri.host ?: ""
                parsedUri.userInfo
                val suffix = cleaned.substringAfter(host)
                val prefix = cleaned.substringBefore(suffix)
                prefix to suffix
            }catch (e: URISyntaxException) {
                "" to cleaned
            }

        }
    }

    private constructor(pair: Pair<String, String>) : this(pair.first, pair.second)
    constructor(uri: String) : this(estimatePrefix(uri))

    val value: String
        get() = "${prefix}${uri}"
    override fun toString() = "<$value>"
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as URIValue

        return other.toString() == toString()
    }

    override fun hashCode(): Int = toString().hashCode()

    open fun prefix(): String = prefix ?: ""
    open fun suffix(): String = uri

}

abstract class VectorValue(val type: Type, val length: Int) : QuadValue(), Serializable {

    enum class Type(val byte: Byte) {
        Double(0) {
            override fun cottontailType(length: Int): org.vitrivr.cottontail.core.types.Types<*> = org.vitrivr.cottontail.core.types.Types.DoubleVector(length)
        },
        Long(1) {
            override fun cottontailType(length: Int): org.vitrivr.cottontail.core.types.Types<*> = org.vitrivr.cottontail.core.types.Types.LongVector(length)
        },
        Float(2) {
            override fun cottontailType(length: Int): org.vitrivr.cottontail.core.types.Types<*> = org.vitrivr.cottontail.core.types.Types.FloatVector(length)
        }
        ;

        abstract fun cottontailType(length: Int): org.vitrivr.cottontail.core.types.Types<*>

        companion object {
            fun fromByte(byte: Byte) = when(byte) {
                0.toByte() -> Double
                1.toByte() -> Long
                2.toByte() -> Float
                else -> throw IllegalArgumentException()
            }
        }

    }

}

class DoubleVectorValue(val vector: DoubleArray) : VectorValue(Type.Double, vector.size), Serializable {

    companion object {
        fun parse(string: String): DoubleVectorValue {

            var s = string.trim()
            s = if (s.startsWith("[")) s.substringAfter('[') else s
            s = if (s.endsWith("^^DoubleVector")) s.substringBefore("^^DoubleVector") else s
            s = if (s.endsWith("]")) s.substringBefore("]") else s

            val numbers = s.split(',').map { it.trim().toDoubleOrNull() }

            if (numbers.any { it == null }) {
                return DoubleVectorValue(DoubleArray(0))
            }

            return DoubleVectorValue(numbers.filterNotNull().toDoubleArray())

        }
    }

    constructor(values: List<Double>) : this(values.toDoubleArray())
    constructor(values: FloatArray) : this(DoubleArray(values.size) { i -> values[i].toDouble() })

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DoubleVectorValue

        return vector.contentEquals(other.vector)
    }

    private val hashCode = vector.contentHashCode()
    override fun hashCode(): Int = hashCode

    override fun toString(): String {
        return vector.joinToString(separator = ", ", prefix = "[", postfix = "]^^DoubleVector")
    }
}

class FloatVectorValue(val vector: FloatArray) : VectorValue(Type.Float, vector.size), Serializable {

    companion object {
        fun parse(string: String): FloatVectorValue {

            var s = string.trim()
            s = if (s.startsWith("[")) s.substringAfter('[') else s
            s = if (s.endsWith("^^FloatVector")) s.substringBefore("^^FloatVector") else s
            s = if (s.endsWith("]")) s.substringBefore("]") else s

            val numbers = s.split(',').map { it.trim().toFloatOrNull() }

            if (numbers.any { it == null }) {
                return FloatVectorValue(FloatArray(0))
            }

            return FloatVectorValue(numbers.filterNotNull().toFloatArray())

        }
    }

    constructor(values: List<Float>) : this(values.toFloatArray())
    constructor(values: DoubleArray) : this(FloatArray(values.size) { i -> values[i].toFloat() })

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FloatVectorValue

        return vector.contentEquals(other.vector)
    }

    private val hashCode = vector.contentHashCode()
    override fun hashCode(): Int = hashCode

    override fun toString(): String {
        return vector.joinToString(separator = ", ", prefix = "[", postfix = "]^^FloatVector")
    }
}

class LongVectorValue(val vector: LongArray) : VectorValue(Type.Long, vector.size), Serializable {

    companion object {
        fun parse(string: String): LongVectorValue {

            var s = string.trim()
            s = if (s.startsWith("[")) s.substringAfter('[') else s
            s = if (s.endsWith("^^LongVector")) s.substringBefore("^^LongVector") else s
            s = if (s.endsWith("]")) s.substringBefore("]") else s

            val numbers = s.split(',').map { it.trim().toLongOrNull() }

            if (numbers.any { it == null }) {
                return LongVectorValue(LongArray(0))
            }

            return LongVectorValue(numbers.filterNotNull().toLongArray())

        }
    }

    constructor(values: List<Long>) : this(values.toLongArray())
    constructor(values: IntArray) : this(LongArray(values.size) { i -> values[i].toLong() })

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as LongVectorValue

        return vector.contentEquals(other.vector)
    }

    private val hashCode = vector.contentHashCode()
    override fun hashCode(): Int = hashCode

    override fun toString(): String {
        return vector.joinToString(separator = ", ", prefix = "[", postfix = "]^^LongVector")
    }
}

data class TemporalValue(val originalString: String) : Comparable<TemporalValue>, QuadValue(), Serializable {
    private val dateTime: LocalDateTime

    val value: String
        get() = dateTime.toString()

    init {
        val value = if (originalString.contains("^^")) {
            originalString.substringBefore("^^").trim()
        } else {
            originalString
        }
        dateTime = parseDateTime(value)
    }

    private fun parseDateTime(value: String): LocalDateTime {
        val formats = listOf(
            DateTimeFormatter.ISO_DATE_TIME, // ISO-8601 format
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"), // Common database format
            DateTimeFormatter.ISO_DATE // Date only
        )

        formats.forEach { formatter ->
            try {
                return LocalDateTime.parse(value, formatter)
            } catch (e: DateTimeParseException) {
                // Ignore and try the next format
            }
        }

        // Try parsing as a Unix timestamp (milliseconds)
        return try {
            val instant = java.time.Instant.ofEpochMilli(value.toLong())
            LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
        } catch (e: Exception) {
            throw IllegalArgumentException("Unsupported date-time format: $value")
        }
    }

    override fun toString(): String {
        return dateTime.toString()
    }

    override fun compareTo(other: TemporalValue): Int {
        return dateTime.compareTo(other.dateTime)
    }

    operator fun compareTo(other: LocalDateTime): Int {
        return dateTime.compareTo(other)
    }
}
