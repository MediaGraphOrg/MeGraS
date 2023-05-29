package org.megras.util.knn

import kotlin.math.sqrt

sealed class Distance {

    abstract fun distance(a: DoubleArray, b: DoubleArray): Double
    abstract fun distance(a: LongArray, b: LongArray): Double

}

object CosineDistance : Distance() {

    override fun distance(a: DoubleArray, b: DoubleArray): Double {
        if (a.contentEquals(b)) {
            return 0.0
        }

        val len: Int = a.size.coerceAtMost(b.size)

        var dot = 0.0
        var a2 = 0.0
        var b2 = 0.0

        for (i in 0 until len) {
            dot += (a[i] * b[i])
            a2 += (a[i] * a[i])
            b2 += (b[i] * b[i])
        }

        val div = sqrt(a2) * sqrt(b2)

        return if (div < 1e-6 || java.lang.Double.isNaN(div)) {
            1.0
        } else 1.0 - dot / div

    }

    override fun distance(a: LongArray, b: LongArray): Double {
        if (a.contentEquals(b)) {
            return 0.0
        }

        val len: Int = a.size.coerceAtMost(b.size)

        var dot = 0.0
        var a2 = 0.0
        var b2 = 0.0

        for (i in 0 until len) {
            dot += (a[i] * b[i]).toDouble()
            a2 += (a[i] * a[i]).toDouble()
            b2 += (b[i] * b[i]).toDouble()
        }

        val div = sqrt(a2) * sqrt(b2)

        return if (div < 1e-6 || java.lang.Double.isNaN(div)) {
            1.0
        } else 1.0 - dot / div
    }

}