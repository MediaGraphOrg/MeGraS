package org.megras.benchmark.performance

import org.junit.jupiter.api.Test
import org.megras.benchmark.core.*

/**
 * Vector Dimensionality Scalability Benchmark
 *
 * Characterizes performance of the BatchingQueryEngine when interfaced with
 * varying embedding models, identifying potential bottlenecks in high-dimensional
 * vector processing.
 *
 * Tests query performance across different vector dimensionalities:
 * - 256 dimensions
 * - 512 dimensions
 * - 768 dimensions
 * - 1024 dimensions
 *
 * Goal: Characterize performance scaling with vector dimensionality and identify
 * potential bottlenecks in high-dimensional vector processing.
 *
 * Dataset: MeGraS-SYNTH (Multi-dimensional vector extension)
 * Baseline: MeGraS-Optimized (internal scaling)
 *
 * Note: Requires dataset with multi-dimensional vector predicates (vec256, vec512, etc.)
 */
class VectorDimensionalityBenchmark {

    companion object {
        private const val DEFAULT_REPORTS_DIR = "benchmark_reports/performance/scalability/dimensionality"

        /**
         * Dimensionality configurations to test.
         */
        val DIMENSIONALITY_CONFIGS = listOf(
            DimensionalityConfig(256, "vec256"),
            DimensionalityConfig(512, "vec512"),
            DimensionalityConfig(768, "vec768"),
            DimensionalityConfig(1024, "vec1024")
        )
    }

    data class DimensionalityConfig(
        val dimensions: Int,
        val vectorPredicate: String
    )

    data class DimensionalityResult(
        val dimensionalityResults: Map<Int, QueryBenchmarkResult>,
        val scalingFactor: Double,  // ms per dimension increase
        val bottleneckDimension: Int?  // Dimension where performance degrades significantly
    )

    private val config = PerformanceBenchmarkConfig(
        name = "Vector Dimensionality Scalability Benchmark",
        reportsDir = DEFAULT_REPORTS_DIR,
        warmupRuns = 3,
        measuredRuns = 10,
        k = 10,
        infrastructureConfig = InfrastructureConfig(
            megrasServerConfig = MegrasServerConfig(
                objectStoreBase = "store-megras-synth",
                postgresDatabase = "megras_synth"
            )
        )
    )

    @Test
    fun run() {
        val runner = VectorDimensionalityRunner(config, DIMENSIONALITY_CONFIGS)
        try {
            runner.runBenchmark()
        } catch (e: IllegalStateException) {
            println("ERROR: ${e.message}")
        }
    }

    /**
     * Run with custom configuration.
     */
    fun run(customConfig: PerformanceBenchmarkConfig): DimensionalityResult {
        return VectorDimensionalityRunner(customConfig, DIMENSIONALITY_CONFIGS).runBenchmark()
    }

    /**
     * Run for a specific subject URI (useful when testing against known entities).
     */
    fun runForSubject(subjectUri: String): DimensionalityResult {
        return VectorDimensionalityRunner(config, DIMENSIONALITY_CONFIGS)
            .runBenchmarkWithSubject(subjectUri)
    }
}

/**
 * Runner implementation for Vector Dimensionality Scalability benchmark.
 */
class VectorDimensionalityRunner(
    config: PerformanceBenchmarkConfig,
    private val dimensionalityConfigs: List<VectorDimensionalityBenchmark.DimensionalityConfig>
) : PerformanceBenchmarkRunner(config) {

    fun runBenchmark(): VectorDimensionalityBenchmark.DimensionalityResult {
        return runBenchmarkInternal(null)
    }

    fun runBenchmarkWithSubject(subjectUri: String): VectorDimensionalityBenchmark.DimensionalityResult {
        return runBenchmarkInternal(subjectUri)
    }

    private fun runBenchmarkInternal(subjectUri: String?): VectorDimensionalityBenchmark.DimensionalityResult {
        printHeader()

        // Restart infrastructure at the start to ensure clean state
        restartInfrastructure("Starting benchmark with fresh infrastructure...")

        checkEndpoint()

        // If no subject URI provided, retrieve one from the lowest selectivity (0.1% = 1 subject)
        val resolvedSubjectUri = subjectUri ?: run {
            val dbName = config.infrastructureConfig.megrasServerConfig?.postgresDatabase ?: "default"
            println("Retrieving subject URI from lowest selectivity (0.1%) in database '$dbName'...")
            val lowestSelectivityQuery = QueryTemplates.symbolicOnly(QueryTemplates.Selectivity.SEL_0_1_PERCENT)
            val uri = extractSubjectUri(lowestSelectivityQuery)
            if (uri == null) {
                throw IllegalStateException(
                    "No data found in database '$dbName'. Please load the MeGraS-SYNTH dataset first.\n" +
                    "Expected: MeGraS-SYNTH-inflated-*.tsv loaded into '$dbName' database.\n" +
                    "Query tested: SELECT ?s WHERE { ?s synth:sel001 \"true\" . }"
                )
            }
            println("  Using subject URI: $uri")
            println()
            uri
        }

        // Restart infrastructure after retrieving subject URI to ensure clean state for benchmarks
        restartInfrastructure("Restarting infrastructure before dimension benchmarks...")

        println("Experiment: Measuring scalability with vector dimensionality")
        println("Testing dimensions: ${dimensionalityConfigs.map { it.dimensions }.joinToString(", ")}")
        println("Subject URI: $resolvedSubjectUri")
        println()

        val results = mutableMapOf<Int, QueryBenchmarkResult>()

        for ((index, dimConfig) in dimensionalityConfigs.withIndex()) {
            // Restart infrastructure between dimensions to ensure fair cold start comparison
            if (index > 0) {
                restartInfrastructure("Preparing for ${dimConfig.dimensions}d (${dimConfig.vectorPredicate}) benchmark...")
            }

            println("-".repeat(80))
            println("DIMENSIONALITY: ${dimConfig.dimensions}d (${dimConfig.vectorPredicate})")
            println("-".repeat(80))

            val query = QueryTemplates.dimensionalityQuery(
                k = config.k,
                vectorPredicate = dimConfig.vectorPredicate,
                subjectUri = resolvedSubjectUri
            )

            val result = benchmarkQuery(
                queryName = "Dimensionality ${dimConfig.dimensions}d",
                query = query,
                metadata = mapOf(
                    "dimensions" to dimConfig.dimensions,
                    "vectorPredicate" to dimConfig.vectorPredicate
                )
            )

            results[dimConfig.dimensions] = result
            println()
        }

        // Calculate scaling characteristics
        val sortedResults = dimensionalityConfigs.mapNotNull { dimConfig ->
            results[dimConfig.dimensions]?.let { dimConfig.dimensions to it.latencyStats.meanMs }
        }.sortedBy { it.first }

        val scalingFactor = if (sortedResults.size >= 2) {
            val (dim1, lat1) = sortedResults.first()
            val (dim2, lat2) = sortedResults.last()
            if (dim1 != dim2) (lat2 - lat1) / (dim2 - dim1) else 0.0
        } else 0.0

        // Detect bottleneck (where latency increase exceeds 2x the average)
        val bottleneckDimension = detectBottleneck(sortedResults)

        // Print summary
        println("=".repeat(80))
        println("SUMMARY: Vector Dimensionality Scalability")
        println("=".repeat(80))
        println()
        println("Dimensions | Cold Start (ms) | Mean (ms) | Std Dev (ms) | Median (ms) | Throughput (ops/s)")
        println("-----------|-----------------|-----------|--------------|-------------|-------------------")

        for (dimConfig in dimensionalityConfigs) {
            val res = results[dimConfig.dimensions]!!
            val coldStartStr = res.coldStartMs?.toString() ?: "-"
            println("${dimConfig.dimensions.toString().padStart(10)} | " +
                    "${coldStartStr.padStart(15)} | " +
                    "${BenchmarkStatistics.formatMs(res.latencyStats.meanMs).padStart(9)} | " +
                    "${BenchmarkStatistics.formatMs(res.latencyStats.stdDevMs).padStart(12)} | " +
                    "${BenchmarkStatistics.formatMs(res.latencyStats.medianMs).padStart(11)} | " +
                    BenchmarkStatistics.formatOpsPerSec(res.throughputStats.meanOpsPerSec).padStart(17))
        }


        println()
        println("Scaling Factor: ${"%.6f".format(scalingFactor)} ms per dimension")
        if (bottleneckDimension != null) {
            println("⚠ Potential bottleneck detected at ${bottleneckDimension}d")
        } else {
            println("✓ Performance scales smoothly across tested dimensionalities")
        }

        val result = VectorDimensionalityBenchmark.DimensionalityResult(
            dimensionalityResults = results,
            scalingFactor = scalingFactor,
            bottleneckDimension = bottleneckDimension
        )

        saveDimensionalityReports(result)

        // Stop infrastructure at the end
        stopInfrastructure()

        printFooter()
        return result
    }

    private fun detectBottleneck(sortedResults: List<Pair<Int, Double>>): Int? {
        if (sortedResults.size < 3) return null

        val latencyIncreases = sortedResults.zipWithNext { (_, lat1), (dim2, lat2) ->
            dim2 to (lat2 - lat1)
        }

        val avgIncrease = latencyIncreases.map { it.second }.average()
        val threshold = avgIncrease * 2.0  // Bottleneck if increase is 2x average

        return latencyIncreases.find { it.second > threshold }?.first
    }

    private fun saveDimensionalityReports(result: VectorDimensionalityBenchmark.DimensionalityResult) {
        val timestamp = BenchmarkReportGenerator.generateTimestamp()
        val reportsDir = BenchmarkReportGenerator.ensureReportsDir(config.reportsDir)

        // Markdown
        val mdContent = buildString {
            appendLine("# Vector Dimensionality Scalability Benchmark Report")
            appendLine()
            appendLine("Generated: ${BenchmarkReportGenerator.generateReadableTimestamp()}")
            appendLine()
            appendLine("## Experiment Overview")
            appendLine()
            appendLine("This experiment characterizes how the BatchingQueryEngine performs when processing")
            appendLine("vectors of different dimensionalities. Higher dimensions typically require more")
            appendLine("computation for similarity calculations.")
            appendLine()
            appendLine("## Configuration")
            appendLine()
            appendLine("| Parameter | Value |")
            appendLine("|-----------|-------|")
            appendLine("| Endpoint | ${config.sparqlEndpoint} |")
            appendLine("| Warmup Runs | ${config.warmupRuns} |")
            appendLine("| Measured Runs | ${config.measuredRuns} |")
            appendLine("| k (NN) | ${config.k} |")
            appendLine()
            appendLine("## Results by Dimensionality")
            appendLine()
            appendLine("| Dimensions | Vector Predicate | Results | Cold Start (ms) | Min (ms) | Max (ms) | Mean (ms) | Median (ms) | Throughput (ops/s) |")
            appendLine("|------------|------------------|---------|-----------------|----------|----------|-----------|-------------|-------------------|")

            for (dimConfig in dimensionalityConfigs) {
                val res = result.dimensionalityResults[dimConfig.dimensions]!!
                appendLine("| ${dimConfig.dimensions} | ${dimConfig.vectorPredicate} | ${res.resultCount} | ${res.coldStartMs ?: "-"} | " +
                        "${res.latencyStats.minMs} | ${res.latencyStats.maxMs} | " +
                        "${BenchmarkStatistics.formatMs(res.latencyStats.meanMs)} | " +
                        "${BenchmarkStatistics.formatMs(res.latencyStats.medianMs)} | " +
                        "${BenchmarkStatistics.formatOpsPerSec(res.throughputStats.meanOpsPerSec)} |")
            }

            appendLine()
            appendLine("## Analysis")
            appendLine()
            appendLine("| Metric | Value |")
            appendLine("|--------|-------|")
            appendLine("| Scaling Factor | ${"%.6f".format(result.scalingFactor)} ms/dimension |")
            appendLine("| Bottleneck Dimension | ${result.bottleneckDimension ?: "None detected"} |")
            appendLine()
            appendLine("### Interpretation")
            appendLine()
            if (result.bottleneckDimension != null) {
                appendLine("⚠️ **Bottleneck detected at ${result.bottleneckDimension}d**: Performance degrades")
                appendLine("significantly at this dimensionality. Consider optimizing vector indexing or")
                appendLine("using dimensionality reduction techniques for higher-dimensional embeddings.")
            } else {
                appendLine("✅ **Smooth scaling**: Performance scales smoothly across tested dimensionalities.")
                appendLine("The BatchingQueryEngine handles varying embedding sizes efficiently.")
            }
            appendLine()
            appendLine("## Query Template")
            appendLine()
            appendLine("```sparql")
            appendLine(result.dimensionalityResults.values.firstOrNull()?.queryContent ?: "N/A")
            appendLine("```")
        }
        BenchmarkReportGenerator.saveReport(reportsDir, "dimensionality_$timestamp.md", mdContent)

        // CSV
        val csvContent = buildString {
            appendLine("Dimensions,VectorPredicate,Results,ColdStart_ms,Min_ms,Max_ms,Mean_ms,Median_ms,StdDev_ms,P95_ms,P99_ms,Throughput_ops_s")
            for (dimConfig in dimensionalityConfigs) {
                val res = result.dimensionalityResults[dimConfig.dimensions]!!
                appendLine("${dimConfig.dimensions},${dimConfig.vectorPredicate},${res.resultCount},${res.coldStartMs ?: ""}," +
                        "${res.latencyStats.minMs},${res.latencyStats.maxMs}," +
                        "${BenchmarkStatistics.formatMs(res.latencyStats.meanMs)}," +
                        "${BenchmarkStatistics.formatMs(res.latencyStats.medianMs)}," +
                        "${BenchmarkStatistics.formatMs(res.latencyStats.stdDevMs)}," +
                        "${BenchmarkStatistics.formatMs(res.latencyStats.p95Ms)}," +
                        "${BenchmarkStatistics.formatMs(res.latencyStats.p99Ms)}," +
                        "${BenchmarkStatistics.formatOpsPerSec(res.throughputStats.meanOpsPerSec)}")
            }
        }
        BenchmarkReportGenerator.saveReport(reportsDir, "dimensionality_$timestamp.csv", csvContent)

        // JSON
        val jsonData = mapOf(
            "benchmark" to "Vector Dimensionality Scalability",
            "timestamp" to timestamp,
            "config" to mapOf(
                "endpoint" to config.sparqlEndpoint,
                "warmupRuns" to config.warmupRuns,
                "measuredRuns" to config.measuredRuns,
                "k" to config.k
            ),
            "dimensionalityLevels" to dimensionalityConfigs.map { dimConfig ->
                val res = result.dimensionalityResults[dimConfig.dimensions]!!
                mapOf(
                    "dimensions" to dimConfig.dimensions,
                    "vectorPredicate" to dimConfig.vectorPredicate,
                    "resultCount" to res.resultCount,
                    "coldStartMs" to res.coldStartMs,
                    "latency" to mapOf(
                        "minMs" to res.latencyStats.minMs,
                        "maxMs" to res.latencyStats.maxMs,
                        "meanMs" to res.latencyStats.meanMs,
                        "medianMs" to res.latencyStats.medianMs,
                        "stdDevMs" to res.latencyStats.stdDevMs,
                        "p95Ms" to res.latencyStats.p95Ms,
                        "p99Ms" to res.latencyStats.p99Ms,
                        "allTimesMs" to res.latencyStats.allTimesMs
                    ),
                    "throughput" to mapOf(
                        "meanOpsPerSec" to res.throughputStats.meanOpsPerSec,
                        "medianOpsPerSec" to res.throughputStats.medianOpsPerSec
                    )
                )
            },
            "analysis" to mapOf(
                "scalingFactor" to result.scalingFactor,
                "bottleneckDimension" to result.bottleneckDimension
            )
        )
        BenchmarkReportGenerator.saveJsonReport(reportsDir, "dimensionality_$timestamp.json", jsonData)
    }
}

