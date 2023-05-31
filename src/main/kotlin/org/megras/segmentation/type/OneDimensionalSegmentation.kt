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
        // TODO: compare to ThreeDimensionalSegmentation
        return false
    }

    override fun contains(rhs: Bounds): Boolean {
        val minT = rhs.getMinT()
        val maxT = rhs.getMaxT()
        if (minT.isNaN() || maxT.isNaN()) return false

        return this.intervals.any { it.low <= minT && it.high >= maxT }
    }

    override fun getDefinition(): String = intervals.joinToString(",") { "${it.low}-${it.high}" }
}

class Time(intervals: List<Interval>) : OneDimensionalSegmentation(intervals), RelativeSegmentation {
    override val segmentationType = SegmentationType.TIME

    override val isRelative = intervals.all { it.low in 0.0 .. 1.0 && it.high in 0.0 .. 1.0 }

    override fun translate(by: Bounds): Segmentation {
        if (by.dimensions == 1) {
            return Time(intervals.map { Interval(it.low + by.getMinX(), it.high + by.getMinX()) })
        }
        return this
    }

    override fun toAbsolute(bounds: Bounds): Segmentation? {
        val factor = bounds.getTDimension()
        if (factor.isNaN()) return null
        return Time(intervals.map { Interval(it.low * factor, it.high * factor) })
    }

    fun getIntervalsToDiscard(): List<Interval> {
        val newIntervals = mutableListOf<Interval>()

        for (i in 0 until intervals.size - 1) {
            newIntervals.add(Interval(intervals[i].high, intervals[i + 1].low))
        }
        return newIntervals
    }
}

class Character(intervals: List<Interval>) : OneDimensionalSegmentation(intervals), RelativeSegmentation {
    override val segmentationType = SegmentationType.CHARACTER

    override val isRelative = intervals.all { it.low in 0.0 .. 1.0 && it.high in 0.0 .. 1.0 }

    override fun translate(by: Bounds): Segmentation {
        if (by.dimensions == 1) {
            return Character(intervals.map { Interval(it.low + by.getMinX(), it.high + by.getMinX()) })
        }
        return this
    }

    override fun toAbsolute(bounds: Bounds): Segmentation? {
        val factor = bounds.getTDimension()
        if (factor.isNaN()) return null
        return Character(intervals.map { Interval(it.low * factor, it.high * factor) })
    }
}

class Page(intervals: List<Interval>) : OneDimensionalSegmentation(intervals), RelativeSegmentation {
    override val segmentationType = SegmentationType.PAGE

    override val isRelative = intervals.all { it.low in 0.0 .. 1.0 && it.high in 0.0 .. 1.0 }

    override fun translate(by: Bounds): Segmentation {
        if (by.dimensions == 1) {
            return Page(intervals.map { Interval(it.low + by.getMinX(), it.high + by.getMinX()) })
        }
        return this
    }

    override fun toAbsolute(bounds: Bounds): Segmentation? {
        val factor = bounds.getTDimension()
        if (factor.isNaN()) return null
        return Page(intervals.map { Interval(it.low * factor, it.high * factor) })
    }
}

class Frequency(val interval: Interval) : OneDimensionalSegmentation(listOf(interval)) {
    override val segmentationType: SegmentationType = SegmentationType.FREQUENCY

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