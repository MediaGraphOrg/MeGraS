package org.megras.benchmark.performance

import org.junit.jupiter.api.Test
import org.megras.benchmark.core.*

/**
 * N+1 Problem / Pushdown Effect Benchmark
 *
 * Demonstrates and measures the N+1 database call problem that occurs with
 * hybrid SPARQL queries combining symbolic filters and vector (k-NN) operations.
 *
 * ## The N+1 Problem
 *
 * When executing a query like:
 * ```sparql
 * SELECT ?s ?neighbor WHERE {
 *     ?s synth:sel01 "true" .           # Returns N subjects
 *     ?s <implicit:10nn/vec512> ?neighbor .  # k-NN for EACH subject
 * }
 * ```
 *
 * **Without batching (DEFAULT engine):**
 * - Query 1: Fetch all N subjects matching the filter
 * - Query 2..N+1: For EACH subject, execute a separate k-NN query
 * - Total: 1 + N database round-trips
 *
 * **With batching (BATCHING engine):**
 * - Subjects and k-NN queries are batched together
 * - Dramatically fewer database round-trips
 *
 * ## Selectivity Impact
 *
 * Higher selectivity (more subjects pass the filter) = more N+1 calls:
 * - 0.1% selectivity (1 subject): 1 + 1 = 2 calls
 * - 1% selectivity (10 subjects): 1 + 10 = 11 calls
 * - 10% selectivity (100 subjects): 1 + 100 = 101 calls
 * - 50% selectivity (500 subjects): 1 + 500 = 501 calls
 *
 * The BATCHING engine should show relatively stable performance across selectivities,
 * while the DEFAULT engine's latency should increase linearly with selectivity.
 *
 * Dataset: MeGraS-SYNTH with pre-tagged selectivity markers
 * Baselines: MeGraS-Default (DEFAULT engine) vs. MeGraS-Optimized (BATCHING engine)
 */
class PushdownEffectBenchmark {

    companion object {
        private const val DEFAULT_REPORTS_DIR = "benchmark_reports/performance/pushdown"

        /** Query engine types for comparison */
        const val ENGINE_DEFAULT = "DEFAULT"
        const val ENGINE_BATCHING = "BATCHING"
    }

    private fun createConfig(queryEngine: String) = PerformanceBenchmarkConfig(
        name = "Pushdown Effect Benchmark ($queryEngine engine)",
        reportsDir = DEFAULT_REPORTS_DIR,
        warmupRuns = 3,
        measuredRuns = 10,
        k = 10,
        infrastructureConfig = InfrastructureConfig(
            megrasServerConfig = MegrasServerConfig(
                objectStoreBase = "store-megras-synth",
                postgresDatabase = "megras_synth",
                sparqlQueryEngine = queryEngine
            )
        )
    )

    data class PushdownResult(
        val selectivityResults: Map<String, QueryBenchmarkResult>,
        val selectivityValues: Map<String, Double>,
        val scalingFactor: Double,  // How latency scales with selectivity
        val queryEngine: String
    )

    data class ComparisonResult(
        val defaultEngineResult: PushdownResult,
        val batchingEngineResult: PushdownResult,
        val speedupBySelectivity: Map<String, Double>  // Speedup factor per selectivity level
    )

    @Test
    fun run() {
        try {
            val comparisonResult = runComparison()
            println()
            println("=".repeat(80))
            println("COMPARISON COMPLETE")
            println("=".repeat(80))
            println()
            println("Speedup (BATCHING vs DEFAULT) by selectivity:")
            for ((marker, speedup) in comparisonResult.speedupBySelectivity) {
                val selectivity = comparisonResult.defaultEngineResult.selectivityValues[marker]!! * 100
                println("  ${selectivity}%: ${"%.2f".format(speedup)}x")
            }
        } catch (e: IllegalStateException) {
            println("ERROR: ${e.message}")
        }
    }

    /**
     * Run comparison between DEFAULT and BATCHING engines.
     */
    fun runComparison(): ComparisonResult {
        println("=".repeat(80))
        println("N+1 PROBLEM BENCHMARK - ENGINE COMPARISON")
        println("=".repeat(80))
        println()
        println("This benchmark compares two query engine configurations:")
        println("  1. BATCHING: Optimized batching query engine (runs first as baseline)")
        println("  2. DEFAULT: Standard Jena query engine (exhibits N+1 problem)")
        println()

        // Run with BATCHING engine first (optimized baseline)
        println("━".repeat(80))
        println("PHASE 1: Running with BATCHING query engine (optimized)")
        println("━".repeat(80))
        val batchingResult = PushdownEffectRunner(createConfig(ENGINE_BATCHING)).runBenchmark()

        // Run with DEFAULT engine
        println()
        println("━".repeat(80))
        println("PHASE 2: Running with DEFAULT query engine (N+1 problem)")
        println("━".repeat(80))
        val defaultResult = PushdownEffectRunner(createConfig(ENGINE_DEFAULT)).runBenchmark()

        // Calculate speedup for each selectivity level
        val speedupBySelectivity = defaultResult.selectivityResults.keys.associateWith { marker ->
            val defaultMs = defaultResult.selectivityResults[marker]!!.latencyStats.meanMs
            val batchingMs = batchingResult.selectivityResults[marker]!!.latencyStats.meanMs
            if (batchingMs > 0) defaultMs / batchingMs else 1.0
        }

        val comparisonResult = ComparisonResult(
            defaultEngineResult = defaultResult,
            batchingEngineResult = batchingResult,
            speedupBySelectivity = speedupBySelectivity
        )

        // Save comparison report
        saveComparisonReport(comparisonResult)

        return comparisonResult
    }

    /**
     * Run with custom configuration (single engine).
     */
    fun run(customConfig: PerformanceBenchmarkConfig): PushdownResult {
        return PushdownEffectRunner(customConfig).runBenchmark()
    }

    private fun saveComparisonReport(result: ComparisonResult) {
        val timestamp = BenchmarkReportGenerator.generateTimestamp()
        val reportsDir = BenchmarkReportGenerator.ensureReportsDir(DEFAULT_REPORTS_DIR)

        val selectivityLevels = listOf(
            QueryTemplates.Selectivity.SEL_0_1_PERCENT to 0.001,
            QueryTemplates.Selectivity.SEL_1_PERCENT to 0.01,
            QueryTemplates.Selectivity.SEL_10_PERCENT to 0.1,
            QueryTemplates.Selectivity.SEL_50_PERCENT to 0.5
        )

        // Markdown comparison report
        val mdContent = buildString {
            appendLine("# N+1 Problem Benchmark - Engine Comparison Report")
            appendLine()
            appendLine("Generated: ${BenchmarkReportGenerator.generateReadableTimestamp()}")
            appendLine()
            appendLine("## Overview")
            appendLine()
            appendLine("This benchmark demonstrates the **N+1 database call problem** that occurs with hybrid")
            appendLine("SPARQL queries combining symbolic filters and vector (k-NN) operations.")
            appendLine()
            appendLine("### The N+1 Problem")
            appendLine()
            appendLine("When a query filters N subjects and then performs a k-NN search for each:")
            appendLine()
            appendLine("| Engine | Database Calls | Description |")
            appendLine("|--------|----------------|-------------|")
            appendLine("| **DEFAULT** | 1 + N | One call to fetch subjects, then N separate k-NN calls |")
            appendLine("| **BATCHING** | ~2-3 | Batched fetching and k-NN operations |")
            appendLine()
            appendLine("### Engine Configurations")
            appendLine()
            appendLine("- **DEFAULT**: Standard Jena query engine (exhibits N+1 problem)")
            appendLine("- **BATCHING**: Optimized batching query engine (solves N+1 problem)")
            appendLine()
            appendLine("## Queries Used")
            appendLine()
            appendLine("The benchmark uses queries that combine symbolic filters with k-NN vector search.")
            appendLine("Each selectivity level controls how many subjects pass the filter, directly affecting")
            appendLine("the number of N+1 database calls in the DEFAULT engine.")
            appendLine()
            appendLine("| Selectivity | Subjects (N) | DEFAULT Engine Calls | Expected Latency Impact |")
            appendLine("|-------------|--------------|---------------------|------------------------|")
            appendLine("| 0.1% | ~1 | 1 + 1 = 2 | Baseline |")
            appendLine("| 1% | ~10 | 1 + 10 = 11 | ~5x baseline |")
            appendLine("| 10% | ~100 | 1 + 100 = 101 | ~50x baseline |")
            appendLine("| 50% | ~500 | 1 + 500 = 501 | ~250x baseline |")
            appendLine()
            for ((marker, selectivity) in selectivityLevels) {
                appendLine("### ${selectivity * 100}% Selectivity Query")
                appendLine()
                appendLine("```sparql")
                appendLine(QueryTemplates.nPlusOneDemo(selectivity = marker, k = 10))
                appendLine("```")
                appendLine()
            }
            appendLine("## Results Comparison")
            appendLine()
            appendLine("| Selectivity | DEFAULT Mean (ms) | BATCHING Mean (ms) | Speedup |")
            appendLine("|-------------|-------------------|-------------------|---------|")

            for ((marker, selectivity) in selectivityLevels) {
                val defaultMs = result.defaultEngineResult.selectivityResults[marker]!!.latencyStats.meanMs
                val batchingMs = result.batchingEngineResult.selectivityResults[marker]!!.latencyStats.meanMs
                val speedup = result.speedupBySelectivity[marker]!!
                appendLine("| ${selectivity * 100}% | ${BenchmarkStatistics.formatMs(defaultMs)} | ${BenchmarkStatistics.formatMs(batchingMs)} | ${"%.2f".format(speedup)}x |")
            }

            appendLine()
            appendLine("## Detailed Results")
            appendLine()
            appendLine("### DEFAULT Engine")
            appendLine()
            appendLine("| Selectivity | Results | Mean (ms) | Median (ms) | Std Dev (ms) | Throughput (ops/s) |")
            appendLine("|-------------|---------|-----------|-------------|--------------|-------------------|")
            for ((marker, selectivity) in selectivityLevels) {
                val res = result.defaultEngineResult.selectivityResults[marker]!!
                appendLine("| ${selectivity * 100}% | ${res.resultCount} | ${BenchmarkStatistics.formatMs(res.latencyStats.meanMs)} | ${BenchmarkStatistics.formatMs(res.latencyStats.medianMs)} | ${BenchmarkStatistics.formatMs(res.latencyStats.stdDevMs)} | ${BenchmarkStatistics.formatOpsPerSec(res.throughputStats.meanOpsPerSec)} |")
            }
            appendLine()
            appendLine("Scaling Factor: ${"%.4f".format(result.defaultEngineResult.scalingFactor)} ms/selectivity")
            appendLine()
            appendLine("### BATCHING Engine")
            appendLine()
            appendLine("| Selectivity | Results | Mean (ms) | Median (ms) | Std Dev (ms) | Throughput (ops/s) |")
            appendLine("|-------------|---------|-----------|-------------|--------------|-------------------|")
            for ((marker, selectivity) in selectivityLevels) {
                val res = result.batchingEngineResult.selectivityResults[marker]!!
                appendLine("| ${selectivity * 100}% | ${res.resultCount} | ${BenchmarkStatistics.formatMs(res.latencyStats.meanMs)} | ${BenchmarkStatistics.formatMs(res.latencyStats.medianMs)} | ${BenchmarkStatistics.formatMs(res.latencyStats.stdDevMs)} | ${BenchmarkStatistics.formatOpsPerSec(res.throughputStats.meanOpsPerSec)} |")
            }
            appendLine()
            appendLine("Scaling Factor: ${"%.4f".format(result.batchingEngineResult.scalingFactor)} ms/selectivity")
            appendLine()
            appendLine("## Analysis")
            appendLine()
            val avgSpeedup = result.speedupBySelectivity.values.average()
            appendLine("**Average Speedup**: ${"%.2f".format(avgSpeedup)}x")
            appendLine()
            if (avgSpeedup > 1.0) {
                appendLine("✅ The BATCHING engine shows improved performance over the DEFAULT engine.")
            } else {
                appendLine("⚠️ The BATCHING engine does not show improvement over the DEFAULT engine in this test.")
            }
        }
        BenchmarkReportGenerator.saveReport(reportsDir, "comparison_$timestamp.md", mdContent)

        // CSV comparison
        val csvContent = buildString {
            appendLine("Selectivity,SelectivityMarker,DEFAULT_Mean_ms,BATCHING_Mean_ms,Speedup,DEFAULT_Throughput,BATCHING_Throughput")
            for ((marker, selectivity) in selectivityLevels) {
                val defaultRes = result.defaultEngineResult.selectivityResults[marker]!!
                val batchingRes = result.batchingEngineResult.selectivityResults[marker]!!
                appendLine("$selectivity,$marker,${BenchmarkStatistics.formatMs(defaultRes.latencyStats.meanMs)},${BenchmarkStatistics.formatMs(batchingRes.latencyStats.meanMs)},${"%.4f".format(result.speedupBySelectivity[marker]!!)},${BenchmarkStatistics.formatOpsPerSec(defaultRes.throughputStats.meanOpsPerSec)},${BenchmarkStatistics.formatOpsPerSec(batchingRes.throughputStats.meanOpsPerSec)}")
            }
        }
        BenchmarkReportGenerator.saveReport(reportsDir, "comparison_$timestamp.csv", csvContent)

        // JSON comparison
        val jsonData = mapOf(
            "benchmark" to "N+1 Problem - Engine Comparison",
            "timestamp" to timestamp,
            "engines" to listOf(ENGINE_DEFAULT, ENGINE_BATCHING),
            "queries" to selectivityLevels.associate { (marker, selectivity) ->
                marker to mapOf(
                    "selectivity" to selectivity,
                    "sparql" to QueryTemplates.nPlusOneDemo(selectivity = marker, k = 10)
                )
            },
            "results" to mapOf(
                "default" to selectivityLevels.associate { (marker, selectivity) ->
                    val res = result.defaultEngineResult.selectivityResults[marker]!!
                    marker to mapOf(
                        "selectivity" to selectivity,
                        "resultCount" to res.resultCount,
                        "meanMs" to res.latencyStats.meanMs,
                        "medianMs" to res.latencyStats.medianMs,
                        "throughput" to res.throughputStats.meanOpsPerSec
                    )
                },
                "batching" to selectivityLevels.associate { (marker, selectivity) ->
                    val res = result.batchingEngineResult.selectivityResults[marker]!!
                    marker to mapOf(
                        "selectivity" to selectivity,
                        "resultCount" to res.resultCount,
                        "meanMs" to res.latencyStats.meanMs,
                        "medianMs" to res.latencyStats.medianMs,
                        "throughput" to res.throughputStats.meanOpsPerSec
                    )
                }
            ),
            "speedup" to result.speedupBySelectivity,
            "averageSpeedup" to result.speedupBySelectivity.values.average(),
            "scalingFactors" to mapOf(
                "default" to result.defaultEngineResult.scalingFactor,
                "batching" to result.batchingEngineResult.scalingFactor
            )
        )
        BenchmarkReportGenerator.saveJsonReport(reportsDir, "comparison_$timestamp.json", jsonData)

        println()
        println("Comparison reports saved to: $reportsDir")
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

            // Use nPlusOneDemo query to demonstrate N+1 problem
            val query = QueryTemplates.nPlusOneDemo(
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
            scalingFactor = scalingFactor,
            queryEngine = config.infrastructureConfig.megrasServerConfig?.sparqlQueryEngine ?: "BATCHING"
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
            appendLine("The benchmark uses hybrid queries combining symbolic filters with k-NN vector search.")
            appendLine("Each selectivity level uses a different filter predicate:")
            appendLine()
            for ((marker, selectivity) in selectivityLevels) {
                appendLine("### ${selectivity * 100}% Selectivity Query")
                appendLine()
                appendLine("```sparql")
                appendLine(result.selectivityResults[marker]!!.queryContent)
                appendLine("```")
                appendLine()
            }
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
                    "query" to res.queryContent,
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

