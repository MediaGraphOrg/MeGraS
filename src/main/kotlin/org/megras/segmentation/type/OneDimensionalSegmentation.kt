package org.megras.segmentation.type

import org.megras.segmentation.Bounds
import org.megras.segmentation.SegmentationType

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

open class TemporalSegmentation(override val segmentationType: SegmentationType?, intervals: List<Interval>): OneDimensionalSegmentation(intervals), RelativeSegmentation {
    override var bounds = Bounds().addT(intervals.first().low, intervals.last().high)

    override val isRelative = intervals.all { it.low in 0.0 .. 1.0 && it.high in 0.0 .. 1.0 }

    override fun translate(by: Bounds, plus: Boolean): Segmentation {
        if (by.hasT()) {
            return when (plus) {
                true -> TemporalSegmentation(segmentationType, intervals.map { Interval(it.low + by.getMinT(), it.high + by.getMinT()) })
                false -> TemporalSegmentation(segmentationType, intervals.map { Interval(it.low - by.getMinT(), it.high - by.getMinT()) })
            }
        }
        return this
    }

    override fun toAbsolute(bounds: Bounds): Segmentation? {
        val factor = bounds.getTDimension()
        if (factor.isNaN()) return null
        return TemporalSegmentation(segmentationType, intervals.map { Interval(it.low * factor, it.high * factor) })
    }
}

class Time(intervals: List<Interval>) : TemporalSegmentation(SegmentationType.TIME, intervals)

class Character(intervals: List<Interval>) : TemporalSegmentation(SegmentationType.CHARACTER, intervals)

class Page(intervals: List<Interval>) : TemporalSegmentation(SegmentationType.PAGE, intervals)

class Width(intervals: List<Interval>) : OneDimensionalSegmentation(intervals), RelativeSegmentation {
    override val segmentationType = SegmentationType.WIDTH
    override var bounds = Bounds().addX(intervals.first().low, intervals.last().high)

    override val isRelative = intervals.all { it.low in 0.0 .. 1.0 && it.high in 0.0 .. 1.0 }

    override fun translate(by: Bounds, plus: Boolean): Segmentation {
        if (by.hasX()) {
            return when (plus) {
                true -> Width(intervals.map { Interval(it.low + by.getMinX(), it.high + by.getMinX()) })
                false -> Width(intervals.map { Interval(it.low - by.getMinX(), it.high - by.getMinX()) })
            }
        }
        return this
    }

    override fun toAbsolute(bounds: Bounds): Segmentation? {
        val factor = bounds.getTDimension()
        if (factor.isNaN()) return null
        return Width(intervals.map { Interval(it.low * factor, it.high * factor) })
    }
}

class Height(intervals: List<Interval>) : OneDimensionalSegmentation(intervals), RelativeSegmentation {
    override val segmentationType = SegmentationType.HEIGHT
    override var bounds = Bounds().addY(intervals.first().low, intervals.last().high)

    override val isRelative = intervals.all { it.low in 0.0 .. 1.0 && it.high in 0.0 .. 1.0 }

    override fun translate(by: Bounds, plus: Boolean): Segmentation {
        if (by.hasY()) {
            return when (plus) {
                true -> Height(intervals.map { Interval(it.low + by.getMinY(), it.high + by.getMinY()) })
                false -> Height(intervals.map { Interval(it.low - by.getMinY(), it.high - by.getMinY()) })
            }
        }
        return this
    }

    override fun toAbsolute(bounds: Bounds): Segmentation? {
        val factor = bounds.getTDimension()
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