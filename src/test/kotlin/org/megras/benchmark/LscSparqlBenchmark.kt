package org.megras.benchmark

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * LSC SPARQL Benchmark Test
 *
 * Benchmarks SPARQL queries for the Lifelog Search Challenge (LSC) dataset.
 * Reads SPARQL queries from files in a dedicated folder, warms up the application,
 * runs each query multiple times, and calculates performance statistics.
 *
 * Configuration:
 * - LSC_SPARQL_QUERIES_DIR: Directory containing .sparql files for LSC queries
 * - BASE_URL: The SPARQL endpoint URL
 * - WARMUP_RUNS: Number of warmup iterations (not counted in stats)
 * - BENCHMARK_RUNS: Number of benchmark iterations per query
 */
class LscSparqlBenchmark {

    companion object {
        // Configuration - adjust these as needed
        private const val BASE_URL = "http://localhost:8080/query/sparql"
        private const val WARMUP_RUNS = 3
        private const val BENCHMARK_RUNS = 10
        private const val CONNECT_TIMEOUT_MS = 30000
        private const val READ_TIMEOUT_MS = 60000

        // Output directory for reports
        private const val REPORTS_DIR = "benchmark_reports/lsc"
    }

    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    data class QueryResult(
        val responseTimeMs: Long,
        val resultCount: Int,
        val success: Boolean,
        val errorMessage: String? = null
    )

    data class BenchmarkStats(
        val queryName: String,
        val queryContent: String,
        val minMs: Long,
        val maxMs: Long,
        val meanMs: Double,
        val medianMs: Double,
        val stdDevMs: Double,
        val resultCount: Int,
        val successfulRuns: Int,
        val totalRuns: Int,
        val allTimesMs: List<Long>
    )

    @Test
    fun runLscSparqlBenchmark() {
        println("=".repeat(80))
        println("LSC SPARQL Benchmark Test")
        println("=".repeat(80))

        // Find SPARQL query files
        val queriesDir = findQueriesDirectory()
        if (queriesDir == null || !queriesDir.exists()) {
            println("ERROR: LSC SPARQL queries directory not found!")
            println("Expected location: src/test/resources/lsc_sparql_queries")
            println("Please create the directory and add .sparql files.")
            return
        }

        val queryFiles = queriesDir.listFiles { file ->
            file.isFile && (file.extension == "sparql" || file.extension == "rq")
        }?.sortedBy { it.name }

        if (queryFiles.isNullOrEmpty()) {
            println("ERROR: No SPARQL query files found in ${queriesDir.absolutePath}")
            println("Please add .sparql or .rq files to the directory.")
            return
        }

        println("Found ${queryFiles.size} query file(s) in: ${queriesDir.absolutePath}")
        println("Warmup runs: $WARMUP_RUNS")
        println("Benchmark runs: $BENCHMARK_RUNS")
        println("Endpoint: $BASE_URL")
        println()

        // Check if endpoint is available
        if (!isEndpointAvailable()) {
            println("ERROR: SPARQL endpoint is not available at $BASE_URL")
            println("Please start the MeGraS server before running benchmarks.")
            return
        }

        val allStats = mutableListOf<BenchmarkStats>()

        // Process each query file
        for (queryFile in queryFiles) {
            val queryName = queryFile.nameWithoutExtension
            val queryContent = queryFile.readText().trim()

            println("-".repeat(80))
            println("Query: $queryName")
            println("-".repeat(80))
            println("Content: ${queryContent.take(200)}${if (queryContent.length > 200) "..." else ""}")
            println()

            // Warmup phase
            println("Warming up ($WARMUP_RUNS runs)...")
            repeat(WARMUP_RUNS) { i ->
                val result = executeQuery(queryContent)
                print("  Warmup ${i + 1}: ${result.responseTimeMs}ms")
                if (result.success) {
                    println(" (${result.resultCount} results)")
                } else {
                    println(" ERROR: ${result.errorMessage}")
                }
            }
            println()

            // Benchmark phase
            println("Benchmarking ($BENCHMARK_RUNS runs)...")
            val results = mutableListOf<QueryResult>()
            repeat(BENCHMARK_RUNS) { i ->
                val result = executeQuery(queryContent)
                results.add(result)
                print("  Run ${i + 1}: ${result.responseTimeMs}ms")
                if (result.success) {
                    println(" (${result.resultCount} results)")
                } else {
                    println(" ERROR: ${result.errorMessage}")
                }
            }
            println()

            // Calculate statistics
            val stats = calculateStats(queryName, queryContent, results)
            allStats.add(stats)

            // Print individual query stats
            printStats(stats)
            println()
        }

        // Generate and save report
        val report = generateReport(allStats)
        saveReport(report, allStats)

        println("=".repeat(80))
        println("Benchmark Complete!")
        println("=".repeat(80))
    }

    private fun findQueriesDirectory(): File? {
        // Try multiple possible locations
        val possiblePaths = listOf(
            "src/test/resources/lsc_sparql_queries",
            "../src/test/resources/lsc_sparql_queries",
            "lsc_sparql_queries"
        )

        for (path in possiblePaths) {
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) {
                return dir
            }
        }

        // Try to find it relative to the test class location
        val resourceUrl = this::class.java.classLoader.getResource("lsc_sparql_queries")
        if (resourceUrl != null) {
            return File(resourceUrl.toURI())
        }

        return File("src/test/resources/lsc_sparql_queries")
    }

    private fun isEndpointAvailable(): Boolean {
        return try {
            val url = URL(BASE_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"
            connection.connect()
            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode in 200..499 // Accept any response that isn't a server error
        } catch (e: Exception) {
            false
        }
    }

    private fun executeQuery(query: String): QueryResult {
        val startTime = System.currentTimeMillis()

        return try {
            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
            val url = URL("$BASE_URL?query=$encodedQuery")

            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")

            val responseCode = connection.responseCode
            val responseTime = System.currentTimeMillis() - startTime

            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                val resultCount = parseResultCount(response)
                connection.disconnect()

                QueryResult(
                    responseTimeMs = responseTime,
                    resultCount = resultCount,
                    success = true
                )
            } else {
                val errorStream = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                connection.disconnect()

                QueryResult(
                    responseTimeMs = responseTime,
                    resultCount = 0,
                    success = false,
                    errorMessage = "HTTP $responseCode: $errorStream"
                )
            }
        } catch (e: Exception) {
            val responseTime = System.currentTimeMillis() - startTime
            QueryResult(
                responseTimeMs = responseTime,
                resultCount = 0,
                success = false,
                errorMessage = e.message ?: e.javaClass.simpleName
            )
        }
    }

    private fun parseResultCount(jsonResponse: String): Int {
        return try {
            val json = objectMapper.readTree(jsonResponse)
            val bindings = json.path("results").path("bindings")
            if (bindings.isArray) bindings.size() else 0
        } catch (e: Exception) {
            0
        }
    }

    private fun calculateStats(queryName: String, queryContent: String, results: List<QueryResult>): BenchmarkStats {
        val successfulResults = results.filter { it.success }
        val times = successfulResults.map { it.responseTimeMs }

        if (times.isEmpty()) {
            return BenchmarkStats(
                queryName = queryName,
                queryContent = queryContent,
                minMs = 0,
                maxMs = 0,
                meanMs = 0.0,
                medianMs = 0.0,
                stdDevMs = 0.0,
                resultCount = 0,
                successfulRuns = 0,
                totalRuns = results.size,
                allTimesMs = emptyList()
            )
        }

        val sortedTimes = times.sorted()
        val min = sortedTimes.first()
        val max = sortedTimes.last()
        val mean = times.average()

        val median = if (sortedTimes.size % 2 == 0) {
            (sortedTimes[sortedTimes.size / 2 - 1] + sortedTimes[sortedTimes.size / 2]) / 2.0
        } else {
            sortedTimes[sortedTimes.size / 2].toDouble()
        }

        val variance = times.map { (it - mean).pow(2) }.average()
        val stdDev = sqrt(variance)

        // Use result count from first successful result (should be consistent)
        val resultCount = successfulResults.firstOrNull()?.resultCount ?: 0

        return BenchmarkStats(
            queryName = queryName,
            queryContent = queryContent,
            minMs = min,
            maxMs = max,
            meanMs = mean,
            medianMs = median,
            stdDevMs = stdDev,
            resultCount = resultCount,
            successfulRuns = successfulResults.size,
            totalRuns = results.size,
            allTimesMs = times
        )
    }

    private fun printStats(stats: BenchmarkStats) {
        println("Statistics for: ${stats.queryName}")
        println("  Successful runs: ${stats.successfulRuns}/${stats.totalRuns}")
        println("  Result count:    ${stats.resultCount}")
        println("  Min:             ${stats.minMs} ms")
        println("  Max:             ${stats.maxMs} ms")
        println("  Mean:            ${"%.2f".format(stats.meanMs)} ms")
        println("  Median:          ${"%.2f".format(stats.medianMs)} ms")
        println("  Std Dev:         ${"%.2f".format(stats.stdDevMs)} ms")
    }

    private fun generateReport(allStats: List<BenchmarkStats>): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

        return buildString {
            appendLine("# SPARQL Benchmark Report")
            appendLine()
            appendLine("Generated: $timestamp")
            appendLine("Endpoint: $BASE_URL")
            appendLine("Warmup runs: $WARMUP_RUNS")
            appendLine("Benchmark runs: $BENCHMARK_RUNS")
            appendLine()
            appendLine("## Summary")
            appendLine()
            appendLine("| Query | Min (ms) | Max (ms) | Mean (ms) | Median (ms) | Std Dev (ms) | Results | Success Rate |")
            appendLine("|-------|----------|----------|-----------|-------------|--------------|---------|--------------|")

            for (stats in allStats) {
                appendLine("| ${stats.queryName} | ${stats.minMs} | ${stats.maxMs} | ${"%.2f".format(stats.meanMs)} | ${"%.2f".format(stats.medianMs)} | ${"%.2f".format(stats.stdDevMs)} | ${stats.resultCount} | ${stats.successfulRuns}/${stats.totalRuns} |")
            }

            appendLine()
            appendLine("## Query Details")
            appendLine()

            for (stats in allStats) {
                appendLine("### ${stats.queryName}")
                appendLine()
                appendLine("```sparql")
                appendLine(stats.queryContent)
                appendLine("```")
                appendLine()
                appendLine("- **Min:** ${stats.minMs} ms")
                appendLine("- **Max:** ${stats.maxMs} ms")
                appendLine("- **Mean:** ${"%.2f".format(stats.meanMs)} ms")
                appendLine("- **Median:** ${"%.2f".format(stats.medianMs)} ms")
                appendLine("- **Std Dev:** ${"%.2f".format(stats.stdDevMs)} ms")
                appendLine("- **Result Count:** ${stats.resultCount}")
                appendLine("- **All Times (ms):** ${stats.allTimesMs.joinToString(", ")}")
                appendLine()
            }
        }
    }

    private fun saveReport(report: String, allStats: List<BenchmarkStats>) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val reportsDir = File(REPORTS_DIR)

        if (!reportsDir.exists()) {
            reportsDir.mkdirs()
        }

        // Save Markdown report
        val mdFile = File(reportsDir, "benchmark_report_$timestamp.md")
        mdFile.writeText(report)
        println("Report saved to: ${mdFile.absolutePath}")

        // Save CSV report for easy import into spreadsheets
        val csvFile = File(reportsDir, "benchmark_report_$timestamp.csv")
        csvFile.writeText(buildString {
            appendLine("Query,Min (ms),Max (ms),Mean (ms),Median (ms),Std Dev (ms),Result Count,Successful Runs,Total Runs")
            for (stats in allStats) {
                appendLine("${stats.queryName},${stats.minMs},${stats.maxMs},${"%.2f".format(stats.meanMs)},${"%.2f".format(stats.medianMs)},${"%.2f".format(stats.stdDevMs)},${stats.resultCount},${stats.successfulRuns},${stats.totalRuns}")
            }
        })
        println("CSV saved to: ${csvFile.absolutePath}")

        // Save JSON report for programmatic access
        val jsonFile = File(reportsDir, "benchmark_report_$timestamp.json")
        val jsonReport = mapOf(
            "timestamp" to timestamp,
            "config" to mapOf(
                "endpoint" to BASE_URL,
                "warmupRuns" to WARMUP_RUNS,
                "benchmarkRuns" to BENCHMARK_RUNS
            ),
            "results" to allStats.map { stats ->
                mapOf(
                    "queryName" to stats.queryName,
                    "query" to stats.queryContent,
                    "minMs" to stats.minMs,
                    "maxMs" to stats.maxMs,
                    "meanMs" to stats.meanMs,
                    "medianMs" to stats.medianMs,
                    "stdDevMs" to stats.stdDevMs,
                    "resultCount" to stats.resultCount,
                    "successfulRuns" to stats.successfulRuns,
                    "totalRuns" to stats.totalRuns,
                    "allTimesMs" to stats.allTimesMs
                )
            }
        )
        jsonFile.writeText(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonReport))
        println("JSON saved to: ${jsonFile.absolutePath}")
    }
}

