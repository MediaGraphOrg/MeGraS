package org.megras.benchmark.performance

import org.junit.jupiter.api.Test
import org.megras.benchmark.core.*

/**
 * Graph Volume Scalability Benchmark
 *
 * Evaluates how retrieval performance scales with increasing graph size.
 *
 * Tests query performance across different dataset volumes:
 * - 10^5 triples (100k)
 * - 10^6 triples (1M)
 * - 10^7 triples (10M)
 *
 * Goal: Demonstrate that retrieval latency scales logarithmically with graph size,
 * proving that the engine effectively isolates vector operations from background
 * metadata noise.
 *
 * Dataset: MeGraS-SYNTH (Volume-inflated variants)
 * Baselines: MeGraS-Default vs. MeGraS-Optimized
 *
 * Note: This benchmark requires pre-loaded datasets of different sizes.
 * Run each volume variant separately or use the automated setup.
 */
class GraphVolumeScalabilityBenchmark {

    companion object {
        private const val DEFAULT_REPORTS_DIR = "benchmark_reports/performance/scalability/volume"

        /**
         * Volume configurations to test.
         * Each entry maps a label to (approximate triple count, dataset file).
         */
        val VOLUME_CONFIGS = listOf(
            VolumeConfig("100k", 100_000, QueryTemplates.DatasetFiles.INFLATED_100K),
            VolumeConfig("1M", 1_000_000, QueryTemplates.DatasetFiles.INFLATED_1M),
            VolumeConfig("10M", 10_000_000, QueryTemplates.DatasetFiles.INFLATED_10M)
        )
    }

    data class VolumeConfig(
        val label: String,
        val tripleCount: Long,
        val datasetFile: String
    )

    data class VolumeScalabilityResult(
        val volumeResults: Map<String, QueryBenchmarkResult>,
        val volumeConfigs: List<VolumeConfig>,
        val scalingExponent: Double,  // log-log slope
        val isLogarithmic: Boolean    // true if scaling is sub-linear
    )

    private val config = PerformanceBenchmarkConfig(
        name = "Graph Volume Scalability Benchmark",
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
        val runner = GraphVolumeScalabilityRunner(config, VOLUME_CONFIGS)
        try {
            runner.runBenchmark()
        } catch (e: IllegalStateException) {
            println("ERROR: ${e.message}")
        }
    }

    /**
     * Run benchmark for specific volumes (requires manual dataset loading).
     */
    fun runForCurrentDataset(volumeLabel: String, tripleCount: Long): QueryBenchmarkResult {
        val runner = GraphVolumeScalabilityRunner(config, VOLUME_CONFIGS)
        return runner.runSingleVolume(volumeLabel, tripleCount)
    }
}

/**
 * Runner implementation for Graph Volume Scalability benchmark.
 */
class GraphVolumeScalabilityRunner(
    config: PerformanceBenchmarkConfig,
    private val volumeConfigs: List<GraphVolumeScalabilityBenchmark.VolumeConfig>
) : PerformanceBenchmarkRunner(config) {

    fun runBenchmark(): GraphVolumeScalabilityBenchmark.VolumeScalabilityResult {
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

        println("Experiment: Measuring scalability with graph volume")
        println("Testing volumes: ${volumeConfigs.map { it.label }.joinToString(", ")}")
        println()
        println("NOTE: This benchmark assumes the appropriate dataset is pre-loaded.")
        println("      For automated testing, run each volume separately after loading data.")
        println()

        // For now, run against current dataset (user must ensure correct data is loaded)
        val results = mutableMapOf<String, QueryBenchmarkResult>()

        println("-".repeat(80))
        println("Running scalability query against current dataset")
        println("-".repeat(80))

        val query = QueryTemplates.scalabilityQuery(config.k)
        val result = benchmarkQuery(
            queryName = "Volume Scalability",
            query = query,
            metadata = mapOf("queryType" to "scalability")
        )

        // For a complete benchmark, each volume would be tested separately
        results["current"] = result

        println()
        println("=".repeat(80))
        println("SUMMARY: Graph Volume Scalability")
        println("=".repeat(80))
        println()
        println("Current dataset results:")
        println("  Mean Latency: ${BenchmarkStatistics.formatMs(result.latencyStats.meanMs)} ms")
        println("  Std Dev: ${BenchmarkStatistics.formatMs(result.latencyStats.stdDevMs)} ms")
        println("  Median Latency: ${BenchmarkStatistics.formatMs(result.latencyStats.medianMs)} ms")
        println("  Throughput: ${BenchmarkStatistics.formatOpsPerSec(result.throughputStats.meanOpsPerSec)} ops/s")
        println()
        println("To complete the full scalability analysis:")
        println("  1. Load MeGraS-SYNTH-inflated-100k.tsv and run this benchmark")
        println("  2. Load MeGraS-SYNTH-inflated-1M.tsv and run this benchmark")
        println("  3. Load MeGraS-SYNTH-inflated-10M.tsv and run this benchmark")
        println("  4. Compare results to determine scaling characteristics")

        val benchmarkResult = GraphVolumeScalabilityBenchmark.VolumeScalabilityResult(
            volumeResults = results,
            volumeConfigs = volumeConfigs,
            scalingExponent = 0.0,  // Requires multiple data points
            isLogarithmic = false   // Cannot determine with single point
        )

        saveVolumeReports(benchmarkResult)

        // Stop infrastructure at the end
        stopInfrastructure()

        printFooter()
        return benchmarkResult
    }

    /**
     * Run benchmark for a single known volume.
     */
    fun runSingleVolume(volumeLabel: String, tripleCount: Long): QueryBenchmarkResult {
        checkEndpoint()

        println("-".repeat(80))
        println("VOLUME: $volumeLabel (~$tripleCount triples)")
        println("-".repeat(80))

        val query = QueryTemplates.scalabilityQuery(config.k)
        return benchmarkQuery(
            queryName = "Volume $volumeLabel",
            query = query,
            metadata = mapOf(
                "volume" to volumeLabel,
                "tripleCount" to tripleCount
            )
        )
    }

    private fun saveVolumeReports(result: GraphVolumeScalabilityBenchmark.VolumeScalabilityResult) {
        val timestamp = BenchmarkReportGenerator.generateTimestamp()
        val reportsDir = BenchmarkReportGenerator.ensureReportsDir(config.reportsDir)

        // Markdown
        val mdContent = buildString {
            appendLine("# Graph Volume Scalability Benchmark Report")
            appendLine()
            appendLine("Generated: ${BenchmarkReportGenerator.generateReadableTimestamp()}")
            appendLine()
            appendLine("## Experiment Overview")
            appendLine()
            appendLine("This experiment measures how query performance scales with increasing graph size.")
            appendLine("The goal is to demonstrate logarithmic (or sub-linear) scaling, indicating that")
            appendLine("vector operations are effectively isolated from background metadata.")
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
            appendLine("| Volume | Results | Cold Start (ms) | Min (ms) | Max (ms) | Mean (ms) | Median (ms) | Throughput (ops/s) |")
            appendLine("|--------|---------|-----------------|----------|----------|-----------|-------------|-------------------|")

            for ((volume, res) in result.volumeResults) {
                appendLine("| $volume | ${res.resultCount} | ${res.coldStartMs ?: "-"} | " +
                        "${res.latencyStats.minMs} | ${res.latencyStats.maxMs} | " +
                        "${BenchmarkStatistics.formatMs(res.latencyStats.meanMs)} | " +
                        "${BenchmarkStatistics.formatMs(res.latencyStats.medianMs)} | " +
                        "${BenchmarkStatistics.formatOpsPerSec(res.throughputStats.meanOpsPerSec)} |")
            }

            appendLine()
            appendLine("## Query Template")
            appendLine()
            appendLine("```sparql")
            appendLine(result.volumeResults.values.firstOrNull()?.queryContent ?: "N/A")
            appendLine("```")
            appendLine()
            appendLine("## Expected Datasets")
            appendLine()
            appendLine("| Label | Triple Count | File |")
            appendLine("|-------|--------------|------|")
            for (vc in result.volumeConfigs) {
                appendLine("| ${vc.label} | ${vc.tripleCount} | ${vc.datasetFile} |")
            }
        }
        BenchmarkReportGenerator.saveReport(reportsDir, "volume_scalability_$timestamp.md", mdContent)

        // CSV
        val csvContent = buildString {
            appendLine("Volume,Results,ColdStart_ms,Min_ms,Max_ms,Mean_ms,Median_ms,StdDev_ms,Throughput_ops_s")
            for ((volume, res) in result.volumeResults) {
                appendLine("$volume,${res.resultCount},${res.coldStartMs ?: ""}," +
                        "${res.latencyStats.minMs},${res.latencyStats.maxMs}," +
                        "${BenchmarkStatistics.formatMs(res.latencyStats.meanMs)}," +
                        "${BenchmarkStatistics.formatMs(res.latencyStats.medianMs)}," +
                        "${BenchmarkStatistics.formatMs(res.latencyStats.stdDevMs)}," +
                        "${BenchmarkStatistics.formatOpsPerSec(res.throughputStats.meanOpsPerSec)}")
            }
        }
        BenchmarkReportGenerator.saveReport(reportsDir, "volume_scalability_$timestamp.csv", csvContent)

        // JSON
        val jsonData = mapOf(
            "benchmark" to "Graph Volume Scalability",
            "timestamp" to timestamp,
            "config" to mapOf(
                "endpoint" to config.sparqlEndpoint,
                "warmupRuns" to config.warmupRuns,
                "measuredRuns" to config.measuredRuns,
                "k" to config.k
            ),
            "volumeConfigs" to result.volumeConfigs.map { mapOf(
                "label" to it.label,
                "tripleCount" to it.tripleCount,
                "datasetFile" to it.datasetFile
            )},
            "results" to result.volumeResults.map { (volume, res) ->
                mapOf(
                    "volume" to volume,
                    "resultCount" to res.resultCount,
                    "coldStartMs" to res.coldStartMs,
                    "latency" to mapOf(
                        "minMs" to res.latencyStats.minMs,
                        "maxMs" to res.latencyStats.maxMs,
                        "meanMs" to res.latencyStats.meanMs,
                        "medianMs" to res.latencyStats.medianMs,
                        "stdDevMs" to res.latencyStats.stdDevMs
                    ),
                    "throughput" to mapOf(
                        "meanOpsPerSec" to res.throughputStats.meanOpsPerSec
                    )
                )
            },
            "analysis" to mapOf(
                "scalingExponent" to result.scalingExponent,
                "isLogarithmic" to result.isLogarithmic
            )
        )
        BenchmarkReportGenerator.saveJsonReport(reportsDir, "volume_scalability_$timestamp.json", jsonData)
    }
}

