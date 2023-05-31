package org.megras.segmentation

import kotlinx.serialization.Serializable
import java.awt.Shape

@Serializable
class Bounds {

    private var bounds = DoubleArray(8) { Double.NaN }
    var dimensions = 0

    constructor()

    constructor(boundString: String) {
        bounds = boundString.split(",").map {
            if (it == "-") {
                Double.NaN
            } else {
                it.toDouble()
            }
        }.toDoubleArray()
    }

    constructor(shape: Shape) {
        this.bounds = doubleArrayOf(
            shape.bounds.minX.coerceAtLeast(0.0), shape.bounds.maxX.coerceAtLeast(0.0),
            shape.bounds.minY.coerceAtLeast(0.0), shape.bounds.maxY.coerceAtLeast(0.0),
            Double.NaN, Double.NaN,
            Double.NaN, Double.NaN)
        dimensions = 2
    }

    fun addX(min: Number, max: Number): Bounds {
        this.bounds[0] = min.toDouble().coerceAtLeast(0.0)
        this.bounds[1] = max.toDouble().coerceAtLeast(0.0)
        dimensions++
        return this
    }

    fun addY(min: Number, max: Number): Bounds {
        this.bounds[2] = min.toDouble().coerceAtLeast(0.0)
        this.bounds[3] = max.toDouble().coerceAtLeast(0.0)
        dimensions++
        return this
    }

    fun addZ(min: Number, max: Number): Bounds {
        this.bounds[4] = min.toDouble().coerceAtLeast(0.0)
        this.bounds[5] = max.toDouble().coerceAtLeast(0.0)
        dimensions++
        return this
    }

    fun addT(min: Number, max: Number): Bounds {
        this.bounds[6] = min.toDouble().coerceAtLeast(0.0)
        this.bounds[7] = max.toDouble().coerceAtLeast(0.0)
        dimensions++
        return this
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Bounds) return false
        return this.dimensions == other.dimensions && this.bounds.contentEquals(other.bounds)
    }

    fun contains(rhs: Bounds): Boolean {
        if (this.dimensions > rhs.dimensions) return false
        if (this.hasX() && (this.bounds[0] > rhs.bounds[0] || this.bounds[1] < rhs.bounds[1])) return false
        if (this.hasY() && (this.bounds[2] > rhs.bounds[2] || this.bounds[3] < rhs.bounds[3])) return false
        if (this.hasZ() && (this.bounds[4] > rhs.bounds[4] || this.bounds[5] < rhs.bounds[5])) return false
        if (this.hasT() && (this.bounds[6] > rhs.bounds[6] || this.bounds[7] < rhs.bounds[7])) return false
        return true
    }

    fun overlaps(rhs: Bounds): Boolean {
        return (
            (this.hasX() && rhs.hasX() && this.bounds[0] <= rhs.bounds[1] && this.bounds[1] >= rhs.bounds[0]) ||
            (this.hasY() && rhs.hasY() && this.bounds[2] <= rhs.bounds[3] && this.bounds[3] >= rhs.bounds[2]) ||
            (this.hasZ() && rhs.hasZ() && this.bounds[4] <= rhs.bounds[5] && this.bounds[5] >= rhs.bounds[4]) ||
            (this.hasT() && rhs.hasT() && this.bounds[6] <= rhs.bounds[7] && this.bounds[7] >= rhs.bounds[6])
        )
    }

    fun orthogonalTo(rhs: Bounds): Boolean {
        return !(
            (this.hasX() && rhs.hasX()) ||
            (this.hasY() && rhs.hasY()) ||
            (this.hasZ() && rhs.hasZ()) ||
            (this.hasT() && rhs.hasT())
        )
    }

    fun hasX(): Boolean = !this.bounds[0].isNaN() && !this.bounds[1].isNaN()

    fun hasY(): Boolean = !this.bounds[2].isNaN() && !this.bounds[3].isNaN()

    fun hasZ(): Boolean = !this.bounds[4].isNaN() && !this.bounds[5].isNaN()

    fun hasT(): Boolean = !this.bounds[6].isNaN() && !this.bounds[7].isNaN()

    fun isRelative(): Boolean = this.bounds.any { it > 1.0 }

    fun getMinX(): Double = bounds[0]

    fun getMaxX(): Double = bounds[1]

    fun getMinY(): Double = bounds[2]

    fun getMaxY(): Double = bounds[3]

    fun getMinT(): Double = bounds[4]

    fun getMaxT(): Double = bounds[5]

    fun getXBounds(): DoubleArray = bounds.copyOfRange(0, 2)

    fun getYBounds(): DoubleArray = bounds.copyOfRange(2, 4)

    fun getTBounds(): DoubleArray = bounds.copyOfRange(4, 6)

    fun getXDimension(): Double = bounds[1] - bounds[0]

    fun getYDimension(): Double = bounds[3] - bounds[2]

    fun getTDimension(): Double = bounds[5] - bounds[4]

    override fun toString() = bounds.map { if (it.isNaN()) {"-"} else {it} }.joinToString(",")
    override fun hashCode(): Int {
        return bounds.contentHashCode()
    }
}