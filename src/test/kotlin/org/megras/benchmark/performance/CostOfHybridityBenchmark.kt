package org.megras.benchmark.performance

import org.junit.jupiter.api.Test
import org.megras.benchmark.core.*

/**
 * Cost of Hybridity Benchmark
 *
 * Quantifies the baseline performance overhead introduced by augmenting symbolic
 * patterns with perceptual similarity operators.
 *
 * Compares three query types:
 * 1. Symbolic-only: Pure triple pattern matching
 * 2. Vector-only: Pure k-NN vector search
 * 3. Hybrid: Combined symbolic filter + vector search
 *
 * Goal: Measure the overhead of joint vector-symbolic execution compared to
 * individual component lookups to determine the "cost" of multimodal integration.
 *
 * Dataset: MeGraS-SYNTH (10^7 configuration)
 * Baseline: MeGraS-Optimized (pure symbolic vs. pure vector vs. hybrid)
 */
class CostOfHybridityBenchmark {

    companion object {
        private const val DEFAULT_REPORTS_DIR = "benchmark_reports/performance/hybridity"
    }

    private val config = PerformanceBenchmarkConfig(
        name = "Cost of Hybridity Benchmark",
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

    data class HybridityResult(
        val symbolicResult: QueryBenchmarkResult,
        val vectorResult: QueryBenchmarkResult,
        val hybridResult: QueryBenchmarkResult,
        val hybridityOverheadMs: Double,
        val hybridityOverheadPercent: Double
    )

    @Test
    fun run() {
        val runner = CostOfHybridityRunner(config)
        try {
            runner.runBenchmark()
        } catch (e: IllegalStateException) {
            println("ERROR: ${e.message}")
        }
    }

    /**
     * Run with custom configuration.
     */
    fun run(customConfig: PerformanceBenchmarkConfig): HybridityResult {
        return CostOfHybridityRunner(customConfig).runBenchmark()
    }
}

/**
 * Runner implementation for Cost of Hybridity benchmark.
 */
class CostOfHybridityRunner(config: PerformanceBenchmarkConfig) : PerformanceBenchmarkRunner(config) {

    fun runBenchmark(): CostOfHybridityBenchmark.HybridityResult {
        printHeader()

        // Restart infrastructure at the start to ensure clean state
        restartInfrastructure("Starting benchmark with fresh infrastructure...")

        checkEndpoint()

        // Verify data is loaded
        val dbName = config.infrastructureConfig.megrasServerConfig?.postgresDatabase ?: "default"
        println("Verifying data is loaded in database '$dbName'...")
        val verifyQuery = QueryTemplates.symbolicOnly(QueryTemplates.Selectivity.SEL_1_PERCENT)
        val verifyResult = sparqlClient.executeQuery(verifyQuery)
        if (verifyResult.resultCount == 0) {
            throw IllegalStateException(
                "No data found in database '$dbName'. Please load the MeGraS-SYNTH dataset first.\n" +
                "Expected: MeGraS-SYNTH-inflated-*.tsv loaded into '$dbName' database.\n" +
                "Query tested: SELECT ?s WHERE { ?s synth:sel01 \"true\" . }"
            )
        }
        println("  Found ${verifyResult.resultCount} subjects with sel01 selectivity marker. Data OK.")

        println("Experiment: Measuring cost of hybridity")
        println("Comparing: Symbolic-only vs Vector-only vs Hybrid queries")
        println()

        // 1. Symbolic-only query
        println("-".repeat(80))
        println("1. SYMBOLIC-ONLY QUERY")
        println("-".repeat(80))
        val symbolicQuery = QueryTemplates.symbolicOnly(QueryTemplates.Selectivity.SEL_1_PERCENT)
        val symbolicResult = benchmarkQuery(
            queryName = "Symbolic-only (1% selectivity)",
            query = symbolicQuery,
            metadata = mapOf("queryType" to "symbolic")
        )

        // Extract a subject URI from the lowest selectivity (0.1% = 1 subject) for use in vector-only query
        // This ensures we use a single, specific subject for the vector search
        val lowestSelectivityQuery = QueryTemplates.symbolicOnly(QueryTemplates.Selectivity.SEL_0_1_PERCENT)
        val subjectUri = extractSubjectUri(lowestSelectivityQuery)
        if (subjectUri == null) {
            throw IllegalStateException("Could not extract subject URI from symbolic query results")
        }
        println()
        println("  Extracted subject URI (lowest selectivity) for vector query: $subjectUri")

        // Restart infrastructure before vector-only query
        restartInfrastructure("Preparing for VECTOR-ONLY query...")

        // 2. Vector-only query (using the subject URI from symbolic query)
        println("-".repeat(80))
        println("2. VECTOR-ONLY QUERY")
        println("-".repeat(80))
        val vectorQuery = QueryTemplates.vectorOnly(
            subjectUri = subjectUri,
            k = config.k
        )
        val vectorResult = benchmarkQuery(
            queryName = "Vector-only (${config.k}-NN)",
            query = vectorQuery,
            metadata = mapOf("queryType" to "vector", "subjectUri" to subjectUri)
        )

        // Restart infrastructure before hybrid query
        restartInfrastructure("Preparing for HYBRID query...")

        // 3. Hybrid query
        println("-".repeat(80))
        println("3. HYBRID QUERY")
        println("-".repeat(80))
        val hybridQuery = QueryTemplates.hybrid(
            selectivity = QueryTemplates.Selectivity.SEL_0_1_PERCENT,
            k = config.k
        )
        val hybridResult = benchmarkQuery(
            queryName = "Hybrid (0.1% filter + ${config.k}-NN)",
            query = hybridQuery,
            metadata = mapOf("queryType" to "hybrid")
        )

        // Calculate overhead
        val baselineMs = maxOf(symbolicResult.latencyStats.meanMs, vectorResult.latencyStats.meanMs)
        val overheadMs = hybridResult.latencyStats.meanMs - baselineMs
        val overheadPercent = if (baselineMs > 0) (overheadMs / baselineMs) * 100 else 0.0

        // Print summary
        println()
        println("=".repeat(80))
        println("SUMMARY: Cost of Hybridity")
        println("=".repeat(80))
        println()
        println("Query Type          | Mean (ms) | Std Dev (ms) | Median (ms) | Throughput (ops/s)")
        println("--------------------|-----------|--------------|-------------|-------------------")
        println("Symbolic-only       | ${BenchmarkStatistics.formatMs(symbolicResult.latencyStats.meanMs).padStart(9)} | " +
                "${BenchmarkStatistics.formatMs(symbolicResult.latencyStats.stdDevMs).padStart(12)} | " +
                "${BenchmarkStatistics.formatMs(symbolicResult.latencyStats.medianMs).padStart(11)} | " +
                BenchmarkStatistics.formatOpsPerSec(symbolicResult.throughputStats.meanOpsPerSec).padStart(17))
        println("Vector-only         | ${BenchmarkStatistics.formatMs(vectorResult.latencyStats.meanMs).padStart(9)} | " +
                "${BenchmarkStatistics.formatMs(vectorResult.latencyStats.stdDevMs).padStart(12)} | " +
                "${BenchmarkStatistics.formatMs(vectorResult.latencyStats.medianMs).padStart(11)} | " +
                BenchmarkStatistics.formatOpsPerSec(vectorResult.throughputStats.meanOpsPerSec).padStart(17))
        println("Hybrid              | ${BenchmarkStatistics.formatMs(hybridResult.latencyStats.meanMs).padStart(9)} | " +
                "${BenchmarkStatistics.formatMs(hybridResult.latencyStats.stdDevMs).padStart(12)} | " +
                "${BenchmarkStatistics.formatMs(hybridResult.latencyStats.medianMs).padStart(11)} | " +
                BenchmarkStatistics.formatOpsPerSec(hybridResult.throughputStats.meanOpsPerSec).padStart(17))
        println()
        println("Hybridity Overhead: ${"%.2f".format(overheadMs)} ms (${"%.1f".format(overheadPercent)}%)")

        val result = CostOfHybridityBenchmark.HybridityResult(
            symbolicResult = symbolicResult,
            vectorResult = vectorResult,
            hybridResult = hybridResult,
            hybridityOverheadMs = overheadMs,
            hybridityOverheadPercent = overheadPercent
        )

        // Save reports
        saveHybridityReports(result)

        // Stop infrastructure at the end
        stopInfrastructure()

        printFooter()
        return result
    }

    private fun saveHybridityReports(result: CostOfHybridityBenchmark.HybridityResult) {
        val timestamp = BenchmarkReportGenerator.generateTimestamp()
        val reportsDir = BenchmarkReportGenerator.ensureReportsDir(config.reportsDir)

        // Markdown
        val mdContent = buildString {
            appendLine("# Cost of Hybridity Benchmark Report")
            appendLine()
            appendLine("Generated: ${BenchmarkReportGenerator.generateReadableTimestamp()}")
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
            appendLine("## Results")
            appendLine()
            appendLine("| Query Type | Results | Cold Start (ms) | Min (ms) | Max (ms) | Mean (ms) | Median (ms) | Std Dev (ms) | Throughput (ops/s) |")
            appendLine("|------------|---------|-----------------|----------|----------|-----------|-------------|--------------|-------------------|")

            for ((type, res) in listOf(
                "Symbolic-only" to result.symbolicResult,
                "Vector-only" to result.vectorResult,
                "Hybrid" to result.hybridResult
            )) {
                appendLine("| $type | ${res.resultCount} | ${res.coldStartMs ?: "-"} | " +
                        "${res.latencyStats.minMs} | ${res.latencyStats.maxMs} | " +
                        "${BenchmarkStatistics.formatMs(res.latencyStats.meanMs)} | " +
                        "${BenchmarkStatistics.formatMs(res.latencyStats.medianMs)} | " +
                        "${BenchmarkStatistics.formatMs(res.latencyStats.stdDevMs)} | " +
                        "${BenchmarkStatistics.formatOpsPerSec(res.throughputStats.meanOpsPerSec)} |")
            }

            appendLine()
            appendLine("## Hybridity Overhead")
            appendLine()
            appendLine("| Metric | Value |")
            appendLine("|--------|-------|")
            appendLine("| Additional Latency | ${"%.2f".format(result.hybridityOverheadMs)} ms |")
            appendLine("| Overhead Percentage | ${"%.1f".format(result.hybridityOverheadPercent)}% |")
            appendLine()
            appendLine("## Query Details")
            appendLine()
            appendLine("### Symbolic-only Query")
            appendLine("```sparql")
            appendLine(result.symbolicResult.queryContent)
            appendLine("```")
            appendLine()
            appendLine("### Vector-only Query")
            appendLine("```sparql")
            appendLine(result.vectorResult.queryContent)
            appendLine("```")
            appendLine()
            appendLine("### Hybrid Query")
            appendLine("```sparql")
            appendLine(result.hybridResult.queryContent)
            appendLine("```")
        }
        BenchmarkReportGenerator.saveReport(reportsDir, "hybridity_$timestamp.md", mdContent)

        // CSV
        val csvContent = buildString {
            appendLine("QueryType,Results,ColdStart_ms,Min_ms,Max_ms,Mean_ms,Median_ms,StdDev_ms,Throughput_ops_s")
            for ((type, res) in listOf(
                "symbolic" to result.symbolicResult,
                "vector" to result.vectorResult,
                "hybrid" to result.hybridResult
            )) {
                appendLine("$type,${res.resultCount},${res.coldStartMs ?: ""}," +
                        "${res.latencyStats.minMs},${res.latencyStats.maxMs}," +
                        "${BenchmarkStatistics.formatMs(res.latencyStats.meanMs)}," +
                        "${BenchmarkStatistics.formatMs(res.latencyStats.medianMs)}," +
                        "${BenchmarkStatistics.formatMs(res.latencyStats.stdDevMs)}," +
                        "${BenchmarkStatistics.formatOpsPerSec(res.throughputStats.meanOpsPerSec)}")
            }
        }
        BenchmarkReportGenerator.saveReport(reportsDir, "hybridity_$timestamp.csv", csvContent)

        // JSON
        val jsonData = mapOf(
            "benchmark" to "Cost of Hybridity",
            "timestamp" to timestamp,
            "config" to mapOf(
                "endpoint" to config.sparqlEndpoint,
                "warmupRuns" to config.warmupRuns,
                "measuredRuns" to config.measuredRuns,
                "k" to config.k
            ),
            "results" to mapOf(
                "symbolic" to queryResultToMap(result.symbolicResult),
                "vector" to queryResultToMap(result.vectorResult),
                "hybrid" to queryResultToMap(result.hybridResult)
            ),
            "hybridityOverhead" to mapOf(
                "additionalLatencyMs" to result.hybridityOverheadMs,
                "overheadPercent" to result.hybridityOverheadPercent
            )
        )
        BenchmarkReportGenerator.saveJsonReport(reportsDir, "hybridity_$timestamp.json", jsonData)
    }

    private fun queryResultToMap(result: QueryBenchmarkResult): Map<String, Any?> = mapOf(
        "queryName" to result.queryName,
        "query" to result.queryContent,
        "resultCount" to result.resultCount,
        "coldStartMs" to result.coldStartMs,
        "latency" to mapOf(
            "minMs" to result.latencyStats.minMs,
            "maxMs" to result.latencyStats.maxMs,
            "meanMs" to result.latencyStats.meanMs,
            "medianMs" to result.latencyStats.medianMs,
            "stdDevMs" to result.latencyStats.stdDevMs,
            "p95Ms" to result.latencyStats.p95Ms,
            "p99Ms" to result.latencyStats.p99Ms,
            "allTimesMs" to result.latencyStats.allTimesMs
        ),
        "throughput" to mapOf(
            "meanOpsPerSec" to result.throughputStats.meanOpsPerSec,
            "medianOpsPerSec" to result.throughputStats.medianOpsPerSec
        )
    )
}

