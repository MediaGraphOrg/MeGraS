package org.megras.benchmark.performance

import org.junit.jupiter.api.Test
import org.megras.benchmark.core.*

/**
 * Pushdown Effect Benchmark
 *
 * Evaluates the effectiveness of the Early Binding optimization by varying
 * the restrictive nature of the symbolic filters.
 *
 * Tests how different filter selectivities affect query performance:
 * - 0.1% selectivity (sel001): Very restrictive filter
 * - 1% selectivity (sel01): Moderately restrictive filter
 * - 10% selectivity (sel10): Less restrictive filter
 * - 50% selectivity (sel50): Minimally restrictive filter
 *
 * Goal: Prove that MeGraS-Optimized maintains stable performance as filters
 * become more restrictive by "pushing down" candidates, whereas MeGraS-Default
 * suffers from late-binding overhead.
 *
 * Dataset: MeGraS-SYNTH with pre-tagged selectivity markers
 * Baselines: MeGraS-Default vs. MeGraS-Optimized
 */
class PushdownEffectBenchmark {

    companion object {
        private const val DEFAULT_REPORTS_DIR = "benchmark_reports/performance/pushdown"
    }

    private val config = PerformanceBenchmarkConfig(
        name = "Pushdown Effect Benchmark",
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

    data class PushdownResult(
        val selectivityResults: Map<String, QueryBenchmarkResult>,
        val selectivityValues: Map<String, Double>,
        val scalingFactor: Double  // How latency scales with selectivity
    )

    @Test
    fun run() {
        val runner = PushdownEffectRunner(config)
        try {
            runner.runBenchmark()
        } catch (e: IllegalStateException) {
            println("ERROR: ${e.message}")
        }
    }

    /**
     * Run with custom configuration.
     */
    fun run(customConfig: PerformanceBenchmarkConfig): PushdownResult {
        return PushdownEffectRunner(customConfig).runBenchmark()
    }
}

/**
 * Runner implementation for Pushdown Effect benchmark.
 */
class PushdownEffectRunner(config: PerformanceBenchmarkConfig) : PerformanceBenchmarkRunner(config) {

    // Selectivity levels from MeGraS-SYNTH dataset:
    // sel001 = first 1 subject (0.1%), sel01 = first 10 (1%), sel1 = first 100 (10%), sel5 = first 500 (50%)
    private val selectivityLevels = listOf(
        QueryTemplates.Selectivity.SEL_0_1_PERCENT to 0.001,
        QueryTemplates.Selectivity.SEL_1_PERCENT to 0.01,
        QueryTemplates.Selectivity.SEL_10_PERCENT to 0.1,
        QueryTemplates.Selectivity.SEL_50_PERCENT to 0.5
    )

    fun runBenchmark(): PushdownEffectBenchmark.PushdownResult {
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
        println()

        println("Experiment: Evaluating pushdown effect with varying selectivity")
        println("Testing selectivity levels: ${selectivityLevels.map { "${it.second * 100}%" }.joinToString(", ")}")
        println()

        val results = mutableMapOf<String, QueryBenchmarkResult>()
        val selectivityValues = mutableMapOf<String, Double>()

        for ((index, pair) in selectivityLevels.withIndex()) {
            val (selectivityMarker, selectivityValue) = pair

            // Restart infrastructure between selectivity levels for fair comparison
            if (index > 0) {
                restartInfrastructure("Preparing for ${selectivityValue * 100}% selectivity benchmark...")
            }

            println("-".repeat(80))
            println("SELECTIVITY: ${selectivityValue * 100}% ($selectivityMarker)")
            println("-".repeat(80))

            val query = QueryTemplates.hybrid(
                selectivity = selectivityMarker,
                k = config.k
            )

            val result = benchmarkQuery(
                queryName = "Pushdown ${selectivityValue * 100}%",
                query = query,
                metadata = mapOf(
                    "selectivity" to selectivityValue,
                    "selectivityMarker" to selectivityMarker
                )
            )

            results[selectivityMarker] = result
            selectivityValues[selectivityMarker] = selectivityValue
            println()
        }

        // Calculate scaling factor (how latency changes with selectivity)
        val sortedResults = selectivityLevels.mapNotNull { (marker, _) ->
            results[marker]?.let { selectivityValues[marker]!! to it.latencyStats.meanMs }
        }
        val scalingFactor = if (sortedResults.size >= 2) {
            val (sel1, lat1) = sortedResults.first()
            val (sel2, lat2) = sortedResults.last()
            if (sel1 != sel2) (lat2 - lat1) / (sel2 - sel1) else 0.0
        } else 0.0

        // Print summary
        println("=".repeat(80))
        println("SUMMARY: Pushdown Effect")
        println("=".repeat(80))
        println()
        println("Selectivity (%) | Mean (ms) | Std Dev (ms) | Median (ms) | Results | Throughput (ops/s)")
        println("----------------|-----------|--------------|-------------|---------|-------------------")

        for ((marker, selectivity) in selectivityLevels) {
            val res = results[marker]!!
            println("${(selectivity * 100).toString().padStart(15)} | " +
                    "${BenchmarkStatistics.formatMs(res.latencyStats.meanMs).padStart(9)} | " +
                    "${BenchmarkStatistics.formatMs(res.latencyStats.stdDevMs).padStart(12)} | " +
                    "${BenchmarkStatistics.formatMs(res.latencyStats.medianMs).padStart(11)} | " +
                    "${res.resultCount.toString().padStart(7)} | " +
                    BenchmarkStatistics.formatOpsPerSec(res.throughputStats.meanOpsPerSec).padStart(17))
        }

        println()
        println("Scaling Factor: ${"%.4f".format(scalingFactor)} ms per selectivity unit")
        if (scalingFactor < 10) {
            println("✓ Good pushdown effect: Latency is relatively stable across selectivities")
        } else {
            println("⚠ Potential late-binding: Latency increases significantly with selectivity")
        }

        val result = PushdownEffectBenchmark.PushdownResult(
            selectivityResults = results,
            selectivityValues = selectivityValues,
            scalingFactor = scalingFactor
        )

        // Save reports
        savePushdownReports(result)

        // Stop infrastructure at the end
        stopInfrastructure()

        printFooter()
        return result
    }

    private fun savePushdownReports(result: PushdownEffectBenchmark.PushdownResult) {
        val timestamp = BenchmarkReportGenerator.generateTimestamp()
        val reportsDir = BenchmarkReportGenerator.ensureReportsDir(config.reportsDir)

        // Markdown
        val mdContent = buildString {
            appendLine("# Pushdown Effect Benchmark Report")
            appendLine()
            appendLine("Generated: ${BenchmarkReportGenerator.generateReadableTimestamp()}")
            appendLine()
            appendLine("## Experiment Overview")
            appendLine()
            appendLine("This experiment evaluates the effectiveness of Early Binding optimization by measuring")
            appendLine("query performance across different filter selectivities. Lower selectivity means more")
            appendLine("restrictive filters (fewer results pass through).")
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
            appendLine("## Results by Selectivity")
            appendLine()
            appendLine("| Selectivity | Results | Cold Start (ms) | Min (ms) | Max (ms) | Mean (ms) | Median (ms) | Std Dev (ms) | Throughput (ops/s) |")
            appendLine("|-------------|---------|-----------------|----------|----------|-----------|-------------|--------------|-------------------|")

            for ((marker, selectivity) in selectivityLevels) {
                val res = result.selectivityResults[marker]!!
                appendLine("| ${selectivity * 100}% | ${res.resultCount} | ${res.coldStartMs ?: "-"} | " +
                        "${res.latencyStats.minMs} | ${res.latencyStats.maxMs} | " +
                        "${BenchmarkStatistics.formatMs(res.latencyStats.meanMs)} | " +
                        "${BenchmarkStatistics.formatMs(res.latencyStats.medianMs)} | " +
                        "${BenchmarkStatistics.formatMs(res.latencyStats.stdDevMs)} | " +
                        "${BenchmarkStatistics.formatOpsPerSec(res.throughputStats.meanOpsPerSec)} |")
            }

            appendLine()
            appendLine("## Analysis")
            appendLine()
            appendLine("| Metric | Value |")
            appendLine("|--------|-------|")
            appendLine("| Scaling Factor | ${"%.4f".format(result.scalingFactor)} ms/selectivity |")
            appendLine()
            appendLine("### Interpretation")
            appendLine()
            if (result.scalingFactor < 10) {
                appendLine("✅ **Good pushdown effect**: Query latency remains relatively stable across different")
                appendLine("selectivity levels. This indicates the optimizer is successfully pushing down filters")
                appendLine("before executing expensive vector operations.")
            } else {
                appendLine("⚠️ **Potential late-binding overhead**: Query latency increases significantly with")
                appendLine("selectivity. This may indicate the optimizer is not effectively pushing down filters,")
                appendLine("causing unnecessary vector computations.")
            }
            appendLine()
            appendLine("## Query Template")
            appendLine()
            appendLine("```sparql")
            appendLine(result.selectivityResults.values.first().queryContent)
            appendLine("```")
        }
        BenchmarkReportGenerator.saveReport(reportsDir, "pushdown_$timestamp.md", mdContent)

        // CSV
        val csvContent = buildString {
            appendLine("Selectivity,SelectivityMarker,Results,ColdStart_ms,Min_ms,Max_ms,Mean_ms,Median_ms,StdDev_ms,P95_ms,P99_ms,Throughput_ops_s")
            for ((marker, selectivity) in selectivityLevels) {
                val res = result.selectivityResults[marker]!!
                appendLine("$selectivity,$marker,${res.resultCount},${res.coldStartMs ?: ""}," +
                        "${res.latencyStats.minMs},${res.latencyStats.maxMs}," +
                        "${BenchmarkStatistics.formatMs(res.latencyStats.meanMs)}," +
                        "${BenchmarkStatistics.formatMs(res.latencyStats.medianMs)}," +
                        "${BenchmarkStatistics.formatMs(res.latencyStats.stdDevMs)}," +
                        "${BenchmarkStatistics.formatMs(res.latencyStats.p95Ms)}," +
                        "${BenchmarkStatistics.formatMs(res.latencyStats.p99Ms)}," +
                        "${BenchmarkStatistics.formatOpsPerSec(res.throughputStats.meanOpsPerSec)}")
            }
        }
        BenchmarkReportGenerator.saveReport(reportsDir, "pushdown_$timestamp.csv", csvContent)

        // JSON
        val jsonData = mapOf(
            "benchmark" to "Pushdown Effect",
            "timestamp" to timestamp,
            "config" to mapOf(
                "endpoint" to config.sparqlEndpoint,
                "warmupRuns" to config.warmupRuns,
                "measuredRuns" to config.measuredRuns,
                "k" to config.k
            ),
            "selectivityLevels" to selectivityLevels.map { (marker, value) ->
                val res = result.selectivityResults[marker]!!
                mapOf(
                    "marker" to marker,
                    "selectivity" to value,
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
                "interpretation" to if (result.scalingFactor < 10) "good_pushdown" else "potential_late_binding"
            )
        )
        BenchmarkReportGenerator.saveJsonReport(reportsDir, "pushdown_$timestamp.json", jsonData)
    }
}

