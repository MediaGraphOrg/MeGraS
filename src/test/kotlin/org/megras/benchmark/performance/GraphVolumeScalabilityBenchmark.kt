package org.megras.benchmark.performance

import org.junit.jupiter.api.Test
import org.megras.benchmark.core.*

/**
 * Graph Volume Scalability Benchmark
 *
 * Evaluates how retrieval performance scales with increasing graph size,
 * comparing both the DEFAULT and BATCHING query engines.
 *
 * ## Test Configuration
 * 
 * Three pre-configured databases with different volumes:
 * - megras_synth100k: ~100k triples
 * - megras_synth1m: ~1M triples  
 * - megras_synth: ~10M triples
 *
 * ## Goals
 * 
 * 1. Demonstrate that the BATCHING engine scales better than DEFAULT with increasing volume
 * 2. Show that retrieval latency scales sub-linearly (ideally logarithmically) with graph size
 * 3. Prove that the engine effectively isolates vector operations from background metadata
 *
 * ## Expected Results
 * 
 * - DEFAULT engine: Latency increases significantly with volume due to N+1 problem
 * - BATCHING engine: Latency remains relatively stable due to batched operations
 * - Speedup factor should increase with volume (BATCHING advantage grows with data size)
 *
 * Dataset: MeGraS-SYNTH (Volume-inflated variants in separate databases)
 */
class GraphVolumeScalabilityBenchmark {

    companion object {
        private const val DEFAULT_REPORTS_DIR = "benchmark_reports/performance/scalability/volume"

        /** Query engine types for comparison */
        const val ENGINE_DEFAULT = "DEFAULT"
        const val ENGINE_BATCHING = "BATCHING"

        /**
         * Database configurations for different volumes.
         * Each database contains a different amount of data.
         */
        val VOLUME_CONFIGS = listOf(
            VolumeConfig("100k", 100_000, "megras_synth100k"),
            VolumeConfig("1M", 1_000_000, "megras_synth1m"),
            VolumeConfig("10M", 10_000_000, "megras_synth")
        )
    }

    data class VolumeConfig(
        val label: String,
        val tripleCount: Long,
        val database: String
    )

    data class EngineVolumeResult(
        val volumeResults: Map<String, QueryBenchmarkResult>,
        val queryEngine: String
    )

    data class VolumeScalabilityResult(
        val batchingResults: EngineVolumeResult,
        val defaultResults: EngineVolumeResult,
        val speedupByVolume: Map<String, Double>,
        val volumeConfigs: List<VolumeConfig>,
        val batchingScalingFactor: Double,  // How latency scales with volume for BATCHING
        val defaultScalingFactor: Double    // How latency scales with volume for DEFAULT
    )

    private fun createConfig(queryEngine: String, database: String) = PerformanceBenchmarkConfig(
        name = "Graph Volume Scalability ($queryEngine, $database)",
        reportsDir = DEFAULT_REPORTS_DIR,
        warmupRuns = 3,
        measuredRuns = 10,
        k = 10,
        infrastructureConfig = InfrastructureConfig(
            megrasServerConfig = MegrasServerConfig(
                objectStoreBase = "store-megras-synth",
                postgresDatabase = database,
                sparqlQueryEngine = queryEngine
            )
        )
    )

    @Test
    fun run() {
        try {
            val result = runComparison()
            println()
            println("=".repeat(80))
            println("COMPARISON COMPLETE")
            println("=".repeat(80))
            println()
            println("Speedup (BATCHING vs DEFAULT) by volume:")
            for ((volume, speedup) in result.speedupByVolume) {
                println("  $volume: ${"%.2f".format(speedup)}x")
            }
            println()
            println("Scaling factors (ms per 10x volume increase):")
            println("  BATCHING: ${"%.2f".format(result.batchingScalingFactor)} ms")
            println("  DEFAULT: ${"%.2f".format(result.defaultScalingFactor)} ms")
        } catch (e: IllegalStateException) {
            println("ERROR: ${e.message}")
        }
    }

    /**
     * Run comparison between DEFAULT and BATCHING engines across all volumes.
     */
    fun runComparison(): VolumeScalabilityResult {
        println("=".repeat(80))
        println("GRAPH VOLUME SCALABILITY BENCHMARK - ENGINE COMPARISON")
        println("=".repeat(80))
        println()
        println("This benchmark compares query engine performance across different data volumes:")
        println("  Databases: ${VOLUME_CONFIGS.map { "${it.label} (${it.database})" }.joinToString(", ")}")
        println("  Engines: BATCHING (optimized), DEFAULT (N+1 problem)")
        println()

        // Run BATCHING engine first (optimized baseline)
        println("━".repeat(80))
        println("PHASE 1: BATCHING engine across all volumes")
        println("━".repeat(80))
        val batchingResults = runEngineAcrossVolumes(ENGINE_BATCHING)

        // Run DEFAULT engine
        println()
        println("━".repeat(80))
        println("PHASE 2: DEFAULT engine across all volumes")
        println("━".repeat(80))
        val defaultResults = runEngineAcrossVolumes(ENGINE_DEFAULT)

        // Calculate speedup for each volume
        val speedupByVolume = VOLUME_CONFIGS.associate { vc ->
            val batchingMs = batchingResults.volumeResults[vc.label]?.latencyStats?.meanMs ?: 1.0
            val defaultMs = defaultResults.volumeResults[vc.label]?.latencyStats?.meanMs ?: 1.0
            vc.label to (if (batchingMs > 0) defaultMs / batchingMs else 1.0)
        }

        // Calculate scaling factors (how latency grows with volume)
        val batchingScalingFactor = calculateScalingFactor(batchingResults.volumeResults)
        val defaultScalingFactor = calculateScalingFactor(defaultResults.volumeResults)

        val result = VolumeScalabilityResult(
            batchingResults = batchingResults,
            defaultResults = defaultResults,
            speedupByVolume = speedupByVolume,
            volumeConfigs = VOLUME_CONFIGS,
            batchingScalingFactor = batchingScalingFactor,
            defaultScalingFactor = defaultScalingFactor
        )

        // Save comparison report
        saveComparisonReport(result)

        return result
    }

    private fun runEngineAcrossVolumes(queryEngine: String): EngineVolumeResult {
        val results = mutableMapOf<String, QueryBenchmarkResult>()

        for ((index, vc) in VOLUME_CONFIGS.withIndex()) {
            println()
            println("-".repeat(80))
            println("VOLUME: ${vc.label} (~${vc.tripleCount} triples) - Database: ${vc.database}")
            println("-".repeat(80))

            val config = createConfig(queryEngine, vc.database)
            val runner = GraphVolumeScalabilityRunner(config, vc)

            try {
                val result = runner.runSingleVolume()
                results[vc.label] = result

                println("  Mean: ${BenchmarkStatistics.formatMs(result.latencyStats.meanMs)} ms")
                println("  Median: ${BenchmarkStatistics.formatMs(result.latencyStats.medianMs)} ms")
                println("  Throughput: ${BenchmarkStatistics.formatOpsPerSec(result.throughputStats.meanOpsPerSec)} ops/s")
            } catch (e: Exception) {
                println("  ERROR: ${e.message}")
            }
        }

        return EngineVolumeResult(
            volumeResults = results,
            queryEngine = queryEngine
        )
    }

    private fun calculateScalingFactor(results: Map<String, QueryBenchmarkResult>): Double {
        // Calculate how much latency increases per 10x volume increase
        val sortedResults = VOLUME_CONFIGS.mapNotNull { vc ->
            results[vc.label]?.let { vc.tripleCount to it.latencyStats.meanMs }
        }.sortedBy { it.first }

        if (sortedResults.size < 2) return 0.0

        // Use log-log regression to find scaling factor
        val logVolumes = sortedResults.map { Math.log10(it.first.toDouble()) }
        val logLatencies = sortedResults.map { Math.log10(it.second) }

        // Simple linear regression on log-log scale
        val n = logVolumes.size
        val sumX = logVolumes.sum()
        val sumY = logLatencies.sum()
        val sumXY = logVolumes.zip(logLatencies).sumOf { it.first * it.second }
        val sumX2 = logVolumes.sumOf { it * it }

        val slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX)

        // Return the slope as the scaling factor (how latency scales with volume in log-log space)
        return slope
    }

    private fun saveComparisonReport(result: VolumeScalabilityResult) {
        val timestamp = BenchmarkReportGenerator.generateTimestamp()
        val reportsDir = BenchmarkReportGenerator.ensureReportsDir(DEFAULT_REPORTS_DIR)

        // Markdown comparison report
        val mdContent = buildString {
            appendLine("# Graph Volume Scalability Benchmark - Engine Comparison Report")
            appendLine()
            appendLine("Generated: ${BenchmarkReportGenerator.generateReadableTimestamp()}")
            appendLine()
            appendLine("## Overview")
            appendLine()
            appendLine("This benchmark measures how query performance scales with increasing graph size,")
            appendLine("comparing the BATCHING (optimized) and DEFAULT (N+1 problem) query engines.")
            appendLine()
            appendLine("### Database Configurations")
            appendLine()
            appendLine("| Volume | Triple Count | Database |")
            appendLine("|--------|--------------|----------|")
            for (vc in result.volumeConfigs) {
                appendLine("| ${vc.label} | ${vc.tripleCount} | ${vc.database} |")
            }
            appendLine()
            appendLine("## Query Used")
            appendLine()
            appendLine("```sparql")
            appendLine(QueryTemplates.nPlusOneDemo(k = 10))
            appendLine("```")
            appendLine()
            appendLine("## Results Comparison")
            appendLine()
            appendLine("| Volume | BATCHING Mean (ms) | DEFAULT Mean (ms) | Speedup |")
            appendLine("|--------|-------------------|-------------------|---------|")
            for (vc in result.volumeConfigs) {
                val batchingMs = result.batchingResults.volumeResults[vc.label]?.latencyStats?.meanMs ?: 0.0
                val defaultMs = result.defaultResults.volumeResults[vc.label]?.latencyStats?.meanMs ?: 0.0
                val speedup = result.speedupByVolume[vc.label] ?: 0.0
                appendLine("| ${vc.label} | ${BenchmarkStatistics.formatMs(batchingMs)} | ${BenchmarkStatistics.formatMs(defaultMs)} | ${"%.2f".format(speedup)}x |")
            }
            appendLine()
            appendLine("## Detailed Results")
            appendLine()
            appendLine("### BATCHING Engine")
            appendLine()
            appendLine("| Volume | Results | Cold Start (ms) | Mean (ms) | Median (ms) | Std Dev (ms) | Throughput (ops/s) |")
            appendLine("|--------|---------|-----------------|-----------|-------------|--------------|-------------------|")
            for (vc in result.volumeConfigs) {
                val res = result.batchingResults.volumeResults[vc.label]
                if (res != null) {
                    appendLine("| ${vc.label} | ${res.resultCount} | ${res.coldStartMs ?: "-"} | ${BenchmarkStatistics.formatMs(res.latencyStats.meanMs)} | ${BenchmarkStatistics.formatMs(res.latencyStats.medianMs)} | ${BenchmarkStatistics.formatMs(res.latencyStats.stdDevMs)} | ${BenchmarkStatistics.formatOpsPerSec(res.throughputStats.meanOpsPerSec)} |")
                } else {
                    appendLine("| ${vc.label} | - | - | - | - | - | - |")
                }
            }
            appendLine()
            appendLine("### DEFAULT Engine")
            appendLine()
            appendLine("| Volume | Results | Cold Start (ms) | Mean (ms) | Median (ms) | Std Dev (ms) | Throughput (ops/s) |")
            appendLine("|--------|---------|-----------------|-----------|-------------|--------------|-------------------|")
            for (vc in result.volumeConfigs) {
                val res = result.defaultResults.volumeResults[vc.label]
                if (res != null) {
                    appendLine("| ${vc.label} | ${res.resultCount} | ${res.coldStartMs ?: "-"} | ${BenchmarkStatistics.formatMs(res.latencyStats.meanMs)} | ${BenchmarkStatistics.formatMs(res.latencyStats.medianMs)} | ${BenchmarkStatistics.formatMs(res.latencyStats.stdDevMs)} | ${BenchmarkStatistics.formatOpsPerSec(res.throughputStats.meanOpsPerSec)} |")
                } else {
                    appendLine("| ${vc.label} | - | - | - | - | - | - |")
                }
            }
            appendLine()
            appendLine("## Scaling Analysis")
            appendLine()
            appendLine("| Metric | BATCHING | DEFAULT |")
            appendLine("|--------|----------|---------|")
            appendLine("| Scaling Factor (log-log slope) | ${"%.4f".format(result.batchingScalingFactor)} | ${"%.4f".format(result.defaultScalingFactor)} |")
            appendLine()
            appendLine("### Interpretation")
            appendLine()
            appendLine("- **Scaling Factor < 0.5**: Excellent sub-linear scaling")
            appendLine("- **Scaling Factor ~ 1.0**: Linear scaling (latency doubles when volume doubles)")
            appendLine("- **Scaling Factor > 1.0**: Super-linear scaling (poor performance at scale)")
            appendLine()
            val avgSpeedup = result.speedupByVolume.values.average()
            if (avgSpeedup > 1.5) {
                appendLine("✅ **BATCHING engine shows significant improvement** (avg ${"%.2f".format(avgSpeedup)}x speedup)")
            } else {
                appendLine("⚠️ **Results inconclusive** - average speedup is ${"%.2f".format(avgSpeedup)}x")
            }
            if (result.batchingScalingFactor < result.defaultScalingFactor) {
                appendLine()
                appendLine("✅ **BATCHING scales better** with increasing data volume")
            }
        }
        BenchmarkReportGenerator.saveReport(reportsDir, "comparison_$timestamp.md", mdContent)

        // CSV comparison
        val csvContent = buildString {
            appendLine("Volume,TripleCount,Database,BATCHING_Mean_ms,DEFAULT_Mean_ms,Speedup,BATCHING_Throughput,DEFAULT_Throughput")
            for (vc in result.volumeConfigs) {
                val batchingRes = result.batchingResults.volumeResults[vc.label]
                val defaultRes = result.defaultResults.volumeResults[vc.label]
                appendLine("${vc.label},${vc.tripleCount},${vc.database}," +
                        "${BenchmarkStatistics.formatMs(batchingRes?.latencyStats?.meanMs ?: 0.0)}," +
                        "${BenchmarkStatistics.formatMs(defaultRes?.latencyStats?.meanMs ?: 0.0)}," +
                        "${"%.4f".format(result.speedupByVolume[vc.label] ?: 0.0)}," +
                        "${BenchmarkStatistics.formatOpsPerSec(batchingRes?.throughputStats?.meanOpsPerSec ?: 0.0)}," +
                        "${BenchmarkStatistics.formatOpsPerSec(defaultRes?.throughputStats?.meanOpsPerSec ?: 0.0)}")
            }
        }
        BenchmarkReportGenerator.saveReport(reportsDir, "comparison_$timestamp.csv", csvContent)

        // JSON comparison
        val jsonData = mapOf(
            "benchmark" to "Graph Volume Scalability - Engine Comparison",
            "timestamp" to timestamp,
            "engines" to listOf(ENGINE_BATCHING, ENGINE_DEFAULT),
            "volumeConfigs" to result.volumeConfigs.map { mapOf(
                "label" to it.label,
                "tripleCount" to it.tripleCount,
                "database" to it.database
            )},
            "query" to QueryTemplates.nPlusOneDemo(k = 10),
            "results" to mapOf(
                "batching" to result.volumeConfigs.associate { vc ->
                    val res = result.batchingResults.volumeResults[vc.label]
                    vc.label to mapOf(
                        "resultCount" to (res?.resultCount ?: 0),
                        "coldStartMs" to res?.coldStartMs,
                        "meanMs" to (res?.latencyStats?.meanMs ?: 0.0),
                        "medianMs" to (res?.latencyStats?.medianMs ?: 0.0),
                        "throughput" to (res?.throughputStats?.meanOpsPerSec ?: 0.0)
                    )
                },
                "default" to result.volumeConfigs.associate { vc ->
                    val res = result.defaultResults.volumeResults[vc.label]
                    vc.label to mapOf(
                        "resultCount" to (res?.resultCount ?: 0),
                        "coldStartMs" to res?.coldStartMs,
                        "meanMs" to (res?.latencyStats?.meanMs ?: 0.0),
                        "medianMs" to (res?.latencyStats?.medianMs ?: 0.0),
                        "throughput" to (res?.throughputStats?.meanOpsPerSec ?: 0.0)
                    )
                }
            ),
            "speedup" to result.speedupByVolume,
            "scalingFactors" to mapOf(
                "batching" to result.batchingScalingFactor,
                "default" to result.defaultScalingFactor
            )
        )
        BenchmarkReportGenerator.saveJsonReport(reportsDir, "comparison_$timestamp.json", jsonData)

        println()
        println("Comparison reports saved to: $reportsDir")
    }
}

/**
 * Runner implementation for a single volume benchmark.
 */
class GraphVolumeScalabilityRunner(
    config: PerformanceBenchmarkConfig,
    private val volumeConfig: GraphVolumeScalabilityBenchmark.VolumeConfig
) : PerformanceBenchmarkRunner(config) {

    fun runSingleVolume(): QueryBenchmarkResult {
        // Restart infrastructure with the correct database
        restartInfrastructure("Starting MeGraS with database: ${volumeConfig.database}...")

        checkEndpoint()

        // Verify data is loaded
        println("  Verifying data in ${volumeConfig.database}...")
        val verifyQuery = QueryTemplates.symbolicOnly(QueryTemplates.Selectivity.SEL_1_PERCENT)
        val verifyResult = sparqlClient.executeQuery(verifyQuery)
        if (verifyResult.resultCount == 0) {
            throw IllegalStateException(
                "No data found in database '${volumeConfig.database}'.\n" +
                "Expected: Data with selectivity markers loaded."
            )
        }
        println("  Found ${verifyResult.resultCount} subjects. Data OK.")

        // Use nPlusOneDemo query to properly demonstrate N+1 problem
        val query = QueryTemplates.nPlusOneDemo(
            selectivity = QueryTemplates.Selectivity.SEL_1_PERCENT,
            k = config.k
        )

        val result = benchmarkQuery(
            queryName = "Volume ${volumeConfig.label}",
            query = query,
            metadata = mapOf(
                "volume" to volumeConfig.label,
                "tripleCount" to volumeConfig.tripleCount,
                "database" to volumeConfig.database,
                "queryEngine" to (config.infrastructureConfig.megrasServerConfig?.sparqlQueryEngine ?: "BATCHING")
            )
        )

        // Stop infrastructure after this volume
        stopInfrastructure()

        return result
    }
}

