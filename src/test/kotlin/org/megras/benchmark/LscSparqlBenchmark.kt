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
 * Reads SPARQL queries from files in a dedicated folder and measures performance.
 *
 * The benchmark distinguishes between:
 * - **Cold Run**: First execution of each query (only reported if WARMUP_RUNS = 0, realistic for interactive search)
 * - **Warmup Runs**: Initial executions to warm up JVM/caches (not included in statistics)
 * - **Warm Runs**: Timed executions after warmup (shows optimized performance)
 *
 * Configuration:
 * - LSC_SPARQL_QUERIES_DIR: Directory containing .sparql files for LSC queries
 * - BASE_URL: The SPARQL endpoint URL
 * - WARMUP_RUNS: Number of warmup iterations (not counted in stats)
 * - WARM_RUNS: Number of timed warm iterations
 */
class LscSparqlBenchmark {

    companion object {
        // Configuration - adjust these as needed
        private const val BASE_URL = "http://localhost:8080/query/sparql"
        private const val WARMUP_RUNS = 3   // Number of warmup runs (not measured)
        private const val WARM_RUNS = 10    // Number of timed runs after warmup
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

    /**
     * Complete benchmark result for a query
     */
    data class QueryBenchmarkResult(
        val queryName: String,
        val queryContent: String,
        val coldStartMs: Long?,         // First execution time (only if WARMUP_RUNS > 0)
        val warmStats: BenchmarkStats?  // null if no warm runs configured
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
        println("Warmup runs: $WARMUP_RUNS (not measured, for JVM/cache warmup)")
        println("Warm runs: $WARM_RUNS (measured)")
        println("Endpoint: $BASE_URL")
        println()

        // Check if endpoint is available
        if (!isEndpointAvailable()) {
            println("ERROR: SPARQL endpoint is not available at $BASE_URL")
            println("Please start the MeGraS server before running benchmarks.")
            return
        }

        val allResults = mutableListOf<QueryBenchmarkResult>()

        // Process each query file
        for (queryFile in queryFiles) {
            val queryName = queryFile.nameWithoutExtension
            val queryContent = queryFile.readText().trim()

            println("-".repeat(80))
            println("Query: $queryName")
            println("-".repeat(80))
            println("Content: ${queryContent.take(200)}${if (queryContent.length > 200) "..." else ""}")
            println()

            // Warmup phase (not measured, but first run is reported as cold start)
            var coldStartMs: Long? = null
            if (WARMUP_RUNS > 0) {
                println("Warmup ($WARMUP_RUNS runs, not measured)...")
                repeat(WARMUP_RUNS) { i ->
                    val result = executeQuery(queryContent)
                    if (i == 0) {
                        coldStartMs = result.responseTimeMs
                        print("  Warmup ${i + 1} (cold start): ${result.responseTimeMs}ms")
                    } else {
                        print("  Warmup ${i + 1}: ${result.responseTimeMs}ms")
                    }
                    if (result.success) {
                        println(" (${result.resultCount} results)")
                    } else {
                        println(" ERROR: ${result.errorMessage}")
                    }
                }
                println()
            }

            // Warm runs (measured)
            var warmStats: BenchmarkStats? = null
            if (WARM_RUNS > 0) {
                println("Warm runs ($WARM_RUNS measured executions)...")
                val warmResults = mutableListOf<QueryResult>()
                repeat(WARM_RUNS) { i ->
                    val result = executeQuery(queryContent)
                    warmResults.add(result)
                    // If no warmup runs, first warm run is the cold start
                    if (i == 0 && WARMUP_RUNS == 0) {
                        coldStartMs = result.responseTimeMs
                        print("  Warm ${i + 1} (cold start): ${result.responseTimeMs}ms")
                    } else {
                        print("  Warm ${i + 1}: ${result.responseTimeMs}ms")
                    }
                    if (result.success) {
                        println(" (${result.resultCount} results)")
                    } else {
                        println(" ERROR: ${result.errorMessage}")
                    }
                }
                warmStats = calculateStats(queryName, queryContent, warmResults)
                println()
            }

            val benchmarkResult = QueryBenchmarkResult(
                queryName = queryName,
                queryContent = queryContent,
                coldStartMs = coldStartMs,
                warmStats = warmStats
            )
            allResults.add(benchmarkResult)

            // Print individual query stats
            printQueryResult(benchmarkResult)
            println()
        }

        // Generate and save report
        val report = generateReport(allResults)
        saveReport(report, allResults)

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

    private fun printQueryResult(result: QueryBenchmarkResult) {
        println("Statistics for: ${result.queryName}")

        if (result.coldStartMs != null) {
            println("  Cold start:      ${result.coldStartMs} ms")
        }

        if (result.warmStats != null) {
            println("  Result count:    ${result.warmStats.resultCount}")
            println("  Min:             ${result.warmStats.minMs} ms")
            println("  Max:             ${result.warmStats.maxMs} ms")
            println("  Mean:            ${"%.2f".format(result.warmStats.meanMs)} ms")
            println("  Median:          ${"%.2f".format(result.warmStats.medianMs)} ms")
            println("  Std Dev:         ${"%.2f".format(result.warmStats.stdDevMs)} ms")
            println("  Success rate:    ${result.warmStats.successfulRuns}/${result.warmStats.totalRuns}")
        } else {
            println("  No results (no warm runs configured)")
        }
    }

    private fun generateReport(allResults: List<QueryBenchmarkResult>): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

        return buildString {
            appendLine("# LSC SPARQL Benchmark Report")
            appendLine()
            appendLine("Generated: $timestamp")
            appendLine("Endpoint: $BASE_URL")
            appendLine("Warmup runs: $WARMUP_RUNS (not measured)")
            appendLine("Warm runs: $WARM_RUNS (measured)")
            appendLine()

            // Summary table
            appendLine("## Summary")
            appendLine()
            appendLine("| Query | Results | Cold Start (ms) | Min (ms) | Max (ms) | Mean (ms) | Median (ms) | Std Dev (ms) | Success Rate |")
            appendLine("|-------|---------|-----------------|----------|----------|-----------|-------------|--------------|--------------|")
            for (result in allResults) {
                val warm = result.warmStats
                val coldStart = result.coldStartMs?.toString() ?: "-"
                if (warm != null) {
                    appendLine("| ${result.queryName} | ${warm.resultCount} | $coldStart | ${warm.minMs} | ${warm.maxMs} | ${"%.2f".format(warm.meanMs)} | ${"%.2f".format(warm.medianMs)} | ${"%.2f".format(warm.stdDevMs)} | ${warm.successfulRuns}/${warm.totalRuns} |")
                } else {
                    appendLine("| ${result.queryName} | - | $coldStart | - | - | - | - | - | - |")
                }
            }

            appendLine()
            appendLine("## Query Details")
            appendLine()

            for (result in allResults) {
                appendLine("### ${result.queryName}")
                appendLine()
                appendLine("```sparql")
                appendLine(result.queryContent)
                appendLine("```")
                appendLine()

                if (result.coldStartMs != null) {
                    appendLine("- **Cold Start:** ${result.coldStartMs} ms")
                }

                if (result.warmStats != null) {
                    appendLine()
                    appendLine("#### Warm Statistics (${result.warmStats.totalRuns} runs)")
                    appendLine("- **Min:** ${result.warmStats.minMs} ms")
                    appendLine("- **Max:** ${result.warmStats.maxMs} ms")
                    appendLine("- **Mean:** ${"%.2f".format(result.warmStats.meanMs)} ms")
                    appendLine("- **Median:** ${"%.2f".format(result.warmStats.medianMs)} ms")
                    appendLine("- **Std Dev:** ${"%.2f".format(result.warmStats.stdDevMs)} ms")
                    appendLine("- **Result Count:** ${result.warmStats.resultCount}")
                    appendLine("- **Success Rate:** ${result.warmStats.successfulRuns}/${result.warmStats.totalRuns}")
                    appendLine("- **All Times (ms):** ${result.warmStats.allTimesMs.joinToString(", ")}")
                    appendLine()
                }
            }
        }
    }

    private fun saveReport(report: String, allResults: List<QueryBenchmarkResult>) {
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
            appendLine("Query,Result Count,Cold Start (ms),Min (ms),Max (ms),Mean (ms),Median (ms),Std Dev (ms),Successful Runs,Total Runs")
            for (result in allResults) {
                val warm = result.warmStats
                val coldStart = result.coldStartMs?.toString() ?: ""
                if (warm != null) {
                    appendLine("${result.queryName},${warm.resultCount},$coldStart,${warm.minMs},${warm.maxMs},${"%.2f".format(warm.meanMs)},${"%.2f".format(warm.medianMs)},${"%.2f".format(warm.stdDevMs)},${warm.successfulRuns},${warm.totalRuns}")
                } else {
                    appendLine("${result.queryName},0,$coldStart,,,,,,,")
                }
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
                "warmRuns" to WARM_RUNS
            ),
            "results" to allResults.map { result ->
                mapOf(
                    "queryName" to result.queryName,
                    "query" to result.queryContent,
                    "coldStartMs" to result.coldStartMs,
                    "resultCount" to (result.warmStats?.resultCount ?: 0),
                    "stats" to result.warmStats?.let { warm ->
                        mapOf(
                            "minMs" to warm.minMs,
                            "maxMs" to warm.maxMs,
                            "meanMs" to warm.meanMs,
                            "medianMs" to warm.medianMs,
                            "stdDevMs" to warm.stdDevMs,
                            "successfulRuns" to warm.successfulRuns,
                            "totalRuns" to warm.totalRuns,
                            "allTimesMs" to warm.allTimesMs
                        )
                    }
                )
            }
        )
        jsonFile.writeText(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonReport))
        println("JSON saved to: ${jsonFile.absolutePath}")
    }
}

