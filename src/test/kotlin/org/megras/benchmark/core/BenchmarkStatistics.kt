package org.megras.benchmark.core

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Common statistical calculations used across all benchmark types.
 */
object BenchmarkStatistics {

    /**
     * Latency statistics for a series of measurements.
     */
    data class LatencyStats(
        val minMs: Long,
        val maxMs: Long,
        val meanMs: Double,
        val medianMs: Double,
        val stdDevMs: Double,
        val p95Ms: Double,
        val p99Ms: Double,
        val allTimesMs: List<Long>,
        val successfulRuns: Int,
        val totalRuns: Int
    ) {
        val successRate: Double get() = if (totalRuns > 0) successfulRuns.toDouble() / totalRuns else 0.0

        companion object {
            fun empty() = LatencyStats(0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyList(), 0, 0)
        }
    }

    /**
     * Throughput statistics (operations per second).
     */
    data class ThroughputStats(
        val minOpsPerSec: Double,
        val maxOpsPerSec: Double,
        val meanOpsPerSec: Double,
        val medianOpsPerSec: Double,
        val stdDevOpsPerSec: Double,
        val allRates: List<Double>
    ) {
        companion object {
            fun empty() = ThroughputStats(0.0, 0.0, 0.0, 0.0, 0.0, emptyList())
        }
    }

    /**
     * Calculate latency statistics from a list of response times.
     */
    fun calculateLatencyStats(
        times: List<Long>,
        totalRuns: Int = times.size
    ): LatencyStats {
        if (times.isEmpty()) return LatencyStats.empty()

        val sortedTimes = times.sorted()
        val min = sortedTimes.first()
        val max = sortedTimes.last()
        val mean = times.average()
        val median = percentile(sortedTimes, 50.0)
        val p95 = percentile(sortedTimes, 95.0)
        val p99 = percentile(sortedTimes, 99.0)
        val stdDev = standardDeviation(times, mean)

        return LatencyStats(
            minMs = min,
            maxMs = max,
            meanMs = mean,
            medianMs = median,
            stdDevMs = stdDev,
            p95Ms = p95,
            p99Ms = p99,
            allTimesMs = times,
            successfulRuns = times.size,
            totalRuns = totalRuns
        )
    }

    /**
     * Calculate throughput statistics from latency times.
     */
    fun calculateThroughputFromLatency(times: List<Long>): ThroughputStats {
        if (times.isEmpty()) return ThroughputStats.empty()

        // Convert latency (ms) to operations per second
        val rates = times.map { if (it > 0) 1000.0 / it else 0.0 }
        return calculateThroughputStats(rates)
    }

    /**
     * Calculate throughput statistics from a list of rates.
     */
    fun calculateThroughputStats(rates: List<Double>): ThroughputStats {
        if (rates.isEmpty()) return ThroughputStats.empty()

        val sortedRates = rates.sorted()
        val min = sortedRates.first()
        val max = sortedRates.last()
        val mean = rates.average()
        val median = percentileDouble(sortedRates, 50.0)
        val stdDev = standardDeviationDouble(rates, mean)

        return ThroughputStats(
            minOpsPerSec = min,
            maxOpsPerSec = max,
            meanOpsPerSec = mean,
            medianOpsPerSec = median,
            stdDevOpsPerSec = stdDev,
            allRates = rates
        )
    }

    /**
     * Calculate the percentile value from a sorted list.
     */
    fun percentile(sortedValues: List<Long>, percentile: Double): Double {
        if (sortedValues.isEmpty()) return 0.0
        if (sortedValues.size == 1) return sortedValues[0].toDouble()

        val index = (percentile / 100.0) * (sortedValues.size - 1)
        val lower = index.toInt()
        val upper = (lower + 1).coerceAtMost(sortedValues.size - 1)
        val fraction = index - lower

        return sortedValues[lower] + fraction * (sortedValues[upper] - sortedValues[lower])
    }

    /**
     * Calculate the percentile value from a sorted list of doubles.
     */
    fun percentileDouble(sortedValues: List<Double>, percentile: Double): Double {
        if (sortedValues.isEmpty()) return 0.0
        if (sortedValues.size == 1) return sortedValues[0]

        val index = (percentile / 100.0) * (sortedValues.size - 1)
        val lower = index.toInt()
        val upper = (lower + 1).coerceAtMost(sortedValues.size - 1)
        val fraction = index - lower

        return sortedValues[lower] + fraction * (sortedValues[upper] - sortedValues[lower])
    }

    /**
     * Calculate standard deviation.
     */
    fun standardDeviation(values: List<Long>, mean: Double): Double {
        if (values.isEmpty()) return 0.0
        val variance = values.map { (it - mean).pow(2) }.average()
        return sqrt(variance)
    }

    /**
     * Calculate standard deviation for doubles.
     */
    fun standardDeviationDouble(values: List<Double>, mean: Double): Double {
        if (values.isEmpty()) return 0.0
        val variance = values.map { (it - mean).pow(2) }.average()
        return sqrt(variance)
    }

    /**
     * Format milliseconds for display.
     */
    fun formatMs(ms: Double): String = "%.2f".format(ms)

    /**
     * Format milliseconds for display.
     */
    fun formatMs(ms: Long): String = ms.toString()

    /**
     * Format operations per second for display.
     */
    fun formatOpsPerSec(ops: Double): String = "%.2f".format(ops)
}

