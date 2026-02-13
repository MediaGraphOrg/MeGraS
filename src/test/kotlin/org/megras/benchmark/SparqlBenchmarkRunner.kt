package org.megras.benchmark

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
 * Generic SPARQL Benchmark Runner
 *
 * A configurable benchmark framework for measuring SPARQL query performance.
 * Can be used for different datasets (LSC, COCO, custom) by providing
 * appropriate configuration.
 *
 * Features:
 * - Configurable warmup and measurement runs
 * - Cold start measurement
 * - Statistical analysis (min, max, mean, median, std dev)
 * - Multiple output formats (Markdown, CSV, JSON)
 * - Extensible for different use cases
 *
 * Usage:
 * ```kotlin
 * val config = BenchmarkConfig.lsc()
 * val runner = SparqlBenchmarkRunner(config)
 * runner.runBenchmark()
 * ```
 */
class SparqlBenchmarkRunner(private val config: BenchmarkConfig) {

    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    init {
        config.validate()
    }

    // ============== Data Classes ==============

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

    data class QueryBenchmarkResult(
        val queryName: String,
        val queryContent: String,
        val coldStartMs: Long?,
        val warmStats: BenchmarkStats?
    )

    data class BenchmarkReport(
        val config: BenchmarkConfig,
        val timestamp: String,
        val results: List<QueryBenchmarkResult>,
        val markdownReport: String
    )

    // ============== Public API ==============

    /**
     * Runs the complete benchmark and returns the report.
     *
     * @return BenchmarkReport containing all results and generated reports
     * @throws IllegalStateException if the endpoint is not available
     */
    fun runBenchmark(): BenchmarkReport {
        printHeader()

        val queriesDir = findQueriesDirectory()
        if (queriesDir == null || !queriesDir.exists()) {
            throw IllegalStateException(
                "SPARQL queries directory not found!\n" +
                "Expected location: ${config.queriesDir}\n" +
                "Please create the directory and add .sparql or .rq files."
            )
        }

        val queryFiles = queriesDir.listFiles { file ->
            file.isFile && (file.extension == "sparql" || file.extension == "rq")
        }?.sortedBy { it.name }

        if (queryFiles.isNullOrEmpty()) {
            throw IllegalStateException(
                "No SPARQL query files found in ${queriesDir.absolutePath}\n" +
                "Please add .sparql or .rq files to the directory."
            )
        }

        println("Found ${queryFiles.size} query file(s) in: ${queriesDir.absolutePath}")
        println("Warmup runs: ${config.warmupRuns} (not measured, for JVM/cache warmup)")
        println("Warm runs: ${config.warmRuns} (measured)")
        println("Endpoint: ${config.baseUrl}")
        println()

        if (!isEndpointAvailable()) {
            throw IllegalStateException(
                "SPARQL endpoint is not available at ${config.baseUrl}\n" +
                "Please start the server before running benchmarks."
            )
        }

        val allResults = mutableListOf<QueryBenchmarkResult>()

        for (queryFile in queryFiles) {
            val result = benchmarkQuery(queryFile)
            allResults.add(result)
            printQueryResult(result)
            println()
        }

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val markdownReport = generateMarkdownReport(allResults)

        saveReports(markdownReport, allResults, timestamp)

        printFooter()

        return BenchmarkReport(
            config = config,
            timestamp = timestamp,
            results = allResults,
            markdownReport = markdownReport
        )
    }

    /**
     * Runs the benchmark for a single query string.
     *
     * @param queryName Name identifier for the query
     * @param queryContent SPARQL query string
     * @return QueryBenchmarkResult with statistics
     */
    fun benchmarkSingleQuery(queryName: String, queryContent: String): QueryBenchmarkResult {
        if (!isEndpointAvailable()) {
            throw IllegalStateException("SPARQL endpoint is not available at ${config.baseUrl}")
        }

        return executeBenchmark(queryName, queryContent)
    }

    /**
     * Checks if the SPARQL endpoint is available.
     */
    fun isEndpointAvailable(): Boolean {
        return try {
            val url = URL(config.baseUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"
            connection.connect()
            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode in 200..499
        } catch (e: Exception) {
            false
        }
    }

    // ============== Internal Implementation ==============

    private fun printHeader() {
        println("=".repeat(80))
        println(config.name)
        println("=".repeat(80))
    }

    private fun printFooter() {
        println("=".repeat(80))
        println("Benchmark Complete!")
        println("=".repeat(80))
    }

    private fun findQueriesDirectory(): File? {
        val possiblePaths = listOf(
            config.queriesDir,
            "../${config.queriesDir}",
            config.queriesDir.substringAfterLast("/")
        )

        for (path in possiblePaths) {
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) {
                return dir
            }
        }

        val resourceUrl = this::class.java.classLoader.getResource(
            config.queriesDir.removePrefix("src/test/resources/")
        )
        if (resourceUrl != null) {
            return File(resourceUrl.toURI())
        }

        return File(config.queriesDir)
    }

    private fun benchmarkQuery(queryFile: File): QueryBenchmarkResult {
        val queryName = queryFile.nameWithoutExtension
        val queryContent = queryFile.readText().trim()

        println("-".repeat(80))
        println("Query: $queryName")
        println("-".repeat(80))
        println("Content: ${queryContent.take(200)}${if (queryContent.length > 200) "..." else ""}")
        println()

        return executeBenchmark(queryName, queryContent)
    }

    private fun executeBenchmark(queryName: String, queryContent: String): QueryBenchmarkResult {
        var coldStartMs: Long? = null

        // Warmup phase
        if (config.warmupRuns > 0) {
            println("Warmup (${config.warmupRuns} runs, not measured)...")
            repeat(config.warmupRuns) { i ->
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
        if (config.warmRuns > 0) {
            println("Warm runs (${config.warmRuns} measured executions)...")
            val warmResults = mutableListOf<QueryResult>()
            repeat(config.warmRuns) { i ->
                val result = executeQuery(queryContent)
                warmResults.add(result)
                if (i == 0 && config.warmupRuns == 0) {
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

        return QueryBenchmarkResult(
            queryName = queryName,
            queryContent = queryContent,
            coldStartMs = coldStartMs,
            warmStats = warmStats
        )
    }

    private fun executeQuery(query: String): QueryResult {
        val startTime = System.currentTimeMillis()

        return try {
            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
            val url = URL("${config.baseUrl}?query=$encodedQuery")

            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = config.connectTimeoutMs
            connection.readTimeout = config.readTimeoutMs
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

    private fun generateMarkdownReport(allResults: List<QueryBenchmarkResult>): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

        return buildString {
            appendLine("# ${config.name} Report")
            appendLine()
            appendLine("Generated: $timestamp")
            appendLine("Endpoint: ${config.baseUrl}")
            appendLine("Warmup runs: ${config.warmupRuns} (not measured)")
            appendLine("Warm runs: ${config.warmRuns} (measured)")
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

    private fun saveReports(report: String, allResults: List<QueryBenchmarkResult>, timestamp: String) {
        val reportsDir = File(config.reportsDir)

        if (!reportsDir.exists()) {
            reportsDir.mkdirs()
        }

        // Save Markdown report
        val mdFile = File(reportsDir, "benchmark_report_$timestamp.md")
        mdFile.writeText(report)
        println("Report saved to: ${mdFile.absolutePath}")

        // Save CSV report
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

        // Save JSON report
        val jsonFile = File(reportsDir, "benchmark_report_$timestamp.json")
        val jsonReport = mapOf(
            "benchmark" to config.name,
            "timestamp" to timestamp,
            "config" to mapOf(
                "endpoint" to config.baseUrl,
                "warmupRuns" to config.warmupRuns,
                "warmRuns" to config.warmRuns,
                "connectTimeoutMs" to config.connectTimeoutMs,
                "readTimeoutMs" to config.readTimeoutMs
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

