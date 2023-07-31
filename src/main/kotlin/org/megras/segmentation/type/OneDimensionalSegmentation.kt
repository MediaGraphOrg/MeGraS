package org.megras.segmentation.type

import org.megras.segmentation.Bounds
import org.megras.segmentation.SegmentationType
import kotlin.math.roundToInt

data class Interval(val low: Double, val high: Double)

abstract class OneDimensionalSegmentation(val intervals: List<Interval>) : Segmentation {

    override fun equivalentTo(rhs: Segmentation): Boolean {
        if (rhs !is OneDimensionalSegmentation) return false
        if (this.bounds != rhs.bounds) return false
        return this.intervals == rhs.intervals
    }

    override fun contains(rhs: Segmentation): Boolean {
        if (!this.bounds.contains(rhs.bounds)) return false
        if (rhs is OneDimensionalSegmentation) {
            // All rhs intervals are contained in some intervals
            return rhs.intervals.all { j ->
                this.intervals.any { i -> i.low <= j.low && j.high <= i.high }
            }
        }
        return false
    }

    override fun contains(rhs: Bounds): Boolean {
        val min = when {
            this.bounds.hasX() -> rhs.getMinX()
            this.bounds.hasY() -> rhs.getMinY()
            this.bounds.hasT() -> rhs.getMinT()
            else -> return false
        }
        val max = when {
            this.bounds.hasX() -> rhs.getMaxX()
            this.bounds.hasY() -> rhs.getMaxY()
            this.bounds.hasT() -> rhs.getMaxT()
            else -> return false
        }

        return this.intervals.any { it.low <= min && it.high >= max }
    }

    fun getIntervalsToDiscard(): List<Interval> {
        val newIntervals = mutableListOf<Interval>()

        for (i in 0 until intervals.size - 1) {
            newIntervals.add(Interval(intervals[i].high, intervals[i + 1].low))
        }
        return newIntervals
    }

    override fun getDefinition(): String = intervals.joinToString(",") { "${it.low}-${it.high}" }
}

class Time(intervals: List<Interval>) : OneDimensionalSegmentation(intervals), RelativeSegmentation {
    override val segmentationType = SegmentationType.TIME
    override var bounds = Bounds().addT(intervals.first().low, intervals.last().high)
    override val isRelative = intervals.all { it.low in 0.0..1.0 && it.high in 0.0..1.0 }

    override fun translate(by: Bounds, direction: TranslateDirection): Segmentation {
        if (by.hasT()) {
            return when (direction) {
                TranslateDirection.POSITIVE -> Time(intervals.map { Interval(it.low + by.getMinT(), it.high + by.getMinT()) })
                TranslateDirection.NEGATIVE -> Time(intervals.map { Interval(it.low - by.getMinT(), it.high - by.getMinT()) })
            }
        }
        return this
    }

    override fun toAbsolute(bounds: Bounds): Segmentation? {
        val factor = bounds.getTDimension()
        if (factor.isNaN()) return null
        return Time(intervals.map { Interval(it.low * factor, it.high * factor) })
    }
}

class Character(intervals: List<Interval>) : OneDimensionalSegmentation(intervals), RelativeSegmentation {
    override val segmentationType = SegmentationType.CHARACTER
    override var bounds = Bounds().addT(intervals.first().low, intervals.last().high)
    override val isRelative = intervals.all { it.low in 0.0..1.0 && it.high in 0.0..1.0 }

    override fun translate(by: Bounds, direction: TranslateDirection): Segmentation {
        if (by.hasT()) {
            return when (direction) {
                TranslateDirection.POSITIVE -> Character(intervals.map { Interval(it.low + by.getMinT(), it.high + by.getMinT()) })
                TranslateDirection.NEGATIVE -> Character(intervals.map { Interval(it.low - by.getMinT(), it.high - by.getMinT()) })
            }
        }
        return this
    }

    override fun toAbsolute(bounds: Bounds): Segmentation? {
        val factor = bounds.getTDimension()
        if (factor.isNaN()) return null
        return Character(intervals.map { Interval((it.low * factor).roundToInt().toDouble(), (it.high * factor).roundToInt().toDouble()) })
    }
}

class Page(intervals: List<Interval>) : OneDimensionalSegmentation(intervals), RelativeSegmentation {
    override val segmentationType = SegmentationType.PAGE
    override var bounds = Bounds().addT(intervals.first().low, intervals.last().high)
    override val isRelative = intervals.all { it.low in 0.0..1.0 && it.high in 0.0..1.0 }

    override fun translate(by: Bounds, direction: TranslateDirection): Segmentation {
        if (by.hasT()) {
            return when (direction) {
                TranslateDirection.POSITIVE -> Page(intervals.map { Interval(it.low + by.getMinT(), it.high + by.getMinT()) })
                TranslateDirection.NEGATIVE -> Page(intervals.map { Interval(it.low - by.getMinT(), it.high - by.getMinT()) })
            }
        }
        return this
    }

    override fun toAbsolute(bounds: Bounds): Segmentation? {
        val factor = bounds.getTDimension()
        if (factor.isNaN()) return null
        return Page(intervals.map { Interval((it.low * factor).roundToInt().toDouble(), (it.high * factor).roundToInt().toDouble()) })
    }
}

class Width(intervals: List<Interval>) : OneDimensionalSegmentation(intervals), RelativeSegmentation {
    override val segmentationType = SegmentationType.WIDTH
    override var bounds = Bounds().addX(intervals.first().low, intervals.last().high)
    override val isRelative = intervals.all { it.low in 0.0..1.0 && it.high in 0.0..1.0 }

    override fun translate(by: Bounds, direction: TranslateDirection): Segmentation {
        if (by.hasX()) {
            return when (direction) {
                TranslateDirection.POSITIVE -> Width(intervals.map { Interval(it.low + by.getMinX(), it.high + by.getMinX()) })
                TranslateDirection.NEGATIVE -> Width(intervals.map { Interval(it.low - by.getMinX(), it.high - by.getMinX()) })
            }
        }
        return this
    }

    override fun toAbsolute(bounds: Bounds): Segmentation? {
        val factor = bounds.getXDimension()
        if (factor.isNaN()) return null
        return Width(intervals.map { Interval(it.low * factor, it.high * factor) })
    }
}

class Height(intervals: List<Interval>) : OneDimensionalSegmentation(intervals), RelativeSegmentation {
    override val segmentationType = SegmentationType.HEIGHT
    override var bounds = Bounds().addY(intervals.first().low, intervals.last().high)
    override val isRelative = intervals.all { it.low in 0.0..1.0 && it.high in 0.0..1.0 }

    override fun translate(by: Bounds, direction: TranslateDirection): Segmentation {
        if (by.hasY()) {
            return when (direction) {
                TranslateDirection.POSITIVE -> Height(intervals.map { Interval(it.low + by.getMinY(), it.high + by.getMinY()) })
                TranslateDirection.NEGATIVE -> Height(intervals.map { Interval(it.low - by.getMinY(), it.high - by.getMinY()) })
            }
        }
        return this
    }

    override fun toAbsolute(bounds: Bounds): Segmentation? {
        val factor = bounds.getYDimension()
        if (factor.isNaN()) return null
        return Height(intervals.map { Interval(it.low * factor, it.high * factor) })
    }
}

class Frequency(val interval: Interval) : OneDimensionalSegmentation(listOf(interval)) {
    override val segmentationType: SegmentationType = SegmentationType.FREQUENCY
    override var bounds = Bounds().addT(intervals.first().low, intervals.last().high)

    init {
        require(interval.low <= interval.high) {
            throw IllegalArgumentException("Frequency band is not valid.")
        }
    }

    override fun equivalentTo(rhs: Segmentation): Boolean {
        if (rhs !is Frequency) return false
        return this.interval.low == rhs.interval.low && this.interval.high == rhs.interval.high
    }

    override fun contains(rhs: Segmentation): Boolean {
        if (rhs !is Frequency) return false
        return this.interval.low <= rhs.interval.low && this.interval.high >= rhs.interval.high
    }

    override fun orthogonalTo(rhs: Segmentation): Boolean {
        return rhs !is Frequency
    }

    override fun getDefinition(): String = "$interval.low-$interval.high"
}