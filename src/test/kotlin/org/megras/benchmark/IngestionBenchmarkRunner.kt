package org.megras.benchmark

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.graphstore.MutableQuadSet
import org.megras.graphstore.db.PostgresStore
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Configuration for an ingestion benchmark.
 *
 * @param name Human-readable name for the benchmark (used in reports)
 * @param tsvFile Path to the TSV file to ingest (relative to project root)
 * @param reportsDir Directory where benchmark reports will be saved
 * @param dbHost PostgreSQL host (e.g., "localhost")
 * @param dbPort PostgreSQL port (e.g., 5432)
 * @param dbName PostgreSQL database name (e.g., "megras")
 * @param dbUser PostgreSQL username
 * @param dbPassword PostgreSQL password
 * @param batchSize Number of quads to batch before inserting AND the measurement interval
 * @param clearBeforeRun Whether to clear the database before starting the benchmark
 * @param skipLines Number of lines to skip at the beginning of the file (e.g., for headers)
 */
data class IngestionBenchmarkConfig(
    val name: String,
    val tsvFile: String,
    val reportsDir: String,
    val dbHost: String = "localhost",
    val dbPort: Int = 5432,
    val dbName: String = "megras",
    val dbUser: String = "megras",
    val dbPassword: String = "megras",
    val batchSize: Int = 1000,
    val clearBeforeRun: Boolean = true,
    val skipLines: Int = 0
) {
    /** Combined connection string for PostgresStore */
    val dbConnectionString: String get() = "$dbHost:$dbPort/$dbName"

    fun validate() {
        require(name.isNotBlank()) { "Benchmark name cannot be blank" }
        require(tsvFile.isNotBlank()) { "TSV file path cannot be blank" }
        require(reportsDir.isNotBlank()) { "Reports directory cannot be blank" }
        require(dbHost.isNotBlank()) { "Database host cannot be blank" }
        require(dbPort > 0) { "Database port must be positive" }
        require(dbName.isNotBlank()) { "Database name cannot be blank" }
        require(dbUser.isNotBlank()) { "Database user cannot be blank" }
        require(batchSize > 0) { "Batch size must be positive" }
        require(skipLines >= 0) { "Skip lines must be non-negative" }
    }
}

/**
 * Ingestion Benchmark Runner
 *
 * Measures how ingestion performance changes as the number of items in the store increases.
 *
 * Features:
 * - Tracks ingestion time at configurable intervals
 * - Starts with an empty database (optional clear)
 * - Configurable database connection
 * - Statistical analysis of ingestion rates
 * - Multiple output formats (Markdown, CSV, JSON)
 */
class IngestionBenchmarkRunner(private val config: IngestionBenchmarkConfig) {

    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    init {
        config.validate()
    }

    // ============== Data Classes ==============

    /**
     * A single measurement point during ingestion.
     */
    data class IngestionMeasurement(
        val itemsProcessed: Long,
        val elapsedTimeMs: Long,
        val intervalTimeMs: Long,
        val itemsInInterval: Int,
        val itemsPerSecond: Double
    )

    /**
     * Statistics for the ingestion benchmark.
     */
    data class IngestionStats(
        val totalItems: Long,
        val totalTimeMs: Long,
        val overallItemsPerSecond: Double,
        val minItemsPerSecond: Double,
        val maxItemsPerSecond: Double,
        val meanItemsPerSecond: Double,
        val medianItemsPerSecond: Double,
        val stdDevItemsPerSecond: Double
    )

    /**
     * Complete result of an ingestion benchmark.
     */
    data class IngestionBenchmarkResult(
        val fileName: String,
        val measurements: List<IngestionMeasurement>,
        val stats: IngestionStats,
        val success: Boolean,
        val errorMessage: String? = null
    )

    /**
     * Complete benchmark report.
     */
    data class IngestionBenchmarkReport(
        val config: IngestionBenchmarkConfig,
        val timestamp: String,
        val result: IngestionBenchmarkResult,
        val markdownReport: String
    )

    // ============== Public API ==============

    /**
     * Runs the complete ingestion benchmark and returns the report.
     *
     * @return IngestionBenchmarkReport containing all results and generated reports
     * @throws IllegalStateException if the TSV file is not found or database is not available
     */
    fun runBenchmark(): IngestionBenchmarkReport {
        printHeader()

        val tsvFile = findTsvFile()
        if (!tsvFile.exists()) {
            throw IllegalStateException(
                "TSV file not found!\n" +
                "Expected location: ${config.tsvFile}\n" +
                "Please ensure the file exists."
            )
        }

        println("TSV file: ${tsvFile.absolutePath}")
        println("Database: ${config.dbHost}:${config.dbPort}/${config.dbName}")
        println("Batch size (and measurement interval): ${config.batchSize}")
        println("Clear before run: ${config.clearBeforeRun}")
        println()

        // Clear the database if configured (before creating store)
        if (config.clearBeforeRun) {
            println("Clearing database...")
            clearDatabase()
            println("Database cleared.")
            println()
        }

        // Create the PostgresStore (calls setup() to create tables/indexes)
        val store = createStore()

        // Run the ingestion benchmark
        val result = runIngestion(store, tsvFile)
        printResult(result)

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val markdownReport = generateMarkdownReport(result)

        saveReports(markdownReport, result, timestamp)

        printFooter()

        return IngestionBenchmarkReport(
            config = config,
            timestamp = timestamp,
            result = result,
            markdownReport = markdownReport
        )
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

    private fun findTsvFile(): File {
        val possiblePaths = listOf(
            config.tsvFile,
            "../${config.tsvFile}",
            config.tsvFile.substringAfterLast("/")
        )

        for (path in possiblePaths) {
            val file = File(path)
            if (file.exists() && file.isFile) {
                return file
            }
        }

        return File(config.tsvFile)
    }

    private fun createStore(): PostgresStore {
        return PostgresStore(
            host = config.dbConnectionString,
            user = config.dbUser,
            password = config.dbPassword
        ).also { it.setup() }
    }

    private fun clearDatabase() {
        // Clear all data from the database by dropping and recreating the schema
        val db = Database.connect(
            "jdbc:postgresql://${config.dbConnectionString}",
            driver = "org.postgresql.Driver",
            user = config.dbUser,
            password = config.dbPassword
        )

        transaction(db) {
            // Drop the entire megras schema (this removes all tables, including dynamic vector_values_* tables)
            exec("DROP SCHEMA IF EXISTS megras CASCADE;")
            // Recreate the schema
            exec("CREATE SCHEMA megras;")
        }
    }

    /**
     * Runs the ingestion benchmark using the same parsing logic as ImportCommand.
     * The key difference is that we track timing after each batch to measure performance over time.
     */
    private fun runIngestion(store: MutableQuadSet, tsvFile: File): IngestionBenchmarkResult {
        val measurements = mutableListOf<IngestionMeasurement>()

        // Same parsing setup as ImportCommand
        val splitter = "\t(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)".toRegex()
        val batch = mutableSetOf<Quad>()

        var totalItemsProcessed = 0L
        var skip = config.skipLines
        var lastMeasurementItems = 0L

        val overallStartTime = System.currentTimeMillis()
        var intervalStartTime = overallStartTime

        println("-".repeat(80))
        println("Starting ingestion...")
        println("-".repeat(80))

        try {
            tsvFile.forEachLine { raw ->
                if (skip-- > 0) {
                    return@forEachLine
                }

                val line = raw.split(splitter)
                if (line.size >= 3) {
                    // Same parsing logic as ImportCommand (whitespaceKeep = false)
                    val values = line.map { value ->
                        if (value.startsWith('<') && value.endsWith('>')) {
                            QuadValue.of(value.replace(" ", "_"))
                        } else {
                            QuadValue.of(value)
                        }
                    }
                    val quad = Quad(values[0], values[1], values[2])
                    batch.add(quad)
                    totalItemsProcessed++

                    if (batch.size >= config.batchSize) {
                        store.addAll(batch)
                        batch.clear()

                        // Record measurement after each batch insert
                        val now = System.currentTimeMillis()
                        val elapsedTotal = now - overallStartTime
                        val intervalTime = now - intervalStartTime
                        val itemsInInterval = (totalItemsProcessed - lastMeasurementItems).toInt()
                        val itemsPerSecond = if (intervalTime > 0) {
                            itemsInInterval * 1000.0 / intervalTime
                        } else {
                            0.0
                        }

                        val measurement = IngestionMeasurement(
                            itemsProcessed = totalItemsProcessed,
                            elapsedTimeMs = elapsedTotal,
                            intervalTimeMs = intervalTime,
                            itemsInInterval = itemsInInterval,
                            itemsPerSecond = itemsPerSecond
                        )
                        measurements.add(measurement)

                        println("  ${totalItemsProcessed} items: ${String.format("%.2f", itemsPerSecond)} items/sec (interval: ${intervalTime}ms)")

                        lastMeasurementItems = totalItemsProcessed
                        intervalStartTime = now
                    }
                }
            }

            // Process remaining batch
            if (batch.isNotEmpty()) {
                store.addAll(batch)
                batch.clear()
            }

            // Final measurement
            val finalTime = System.currentTimeMillis()
            val totalElapsed = finalTime - overallStartTime

            if (totalItemsProcessed > lastMeasurementItems) {
                val intervalTime = finalTime - intervalStartTime
                val itemsInInterval = (totalItemsProcessed - lastMeasurementItems).toInt()
                val itemsPerSecond = if (intervalTime > 0) {
                    itemsInInterval * 1000.0 / intervalTime
                } else {
                    0.0
                }

                measurements.add(IngestionMeasurement(
                    itemsProcessed = totalItemsProcessed,
                    elapsedTimeMs = totalElapsed,
                    intervalTimeMs = intervalTime,
                    itemsInInterval = itemsInInterval,
                    itemsPerSecond = itemsPerSecond
                ))
            }

            println()
            println("Ingestion completed: $totalItemsProcessed items in ${totalElapsed}ms")

            val stats = calculateStats(totalItemsProcessed, totalElapsed, measurements)

            return IngestionBenchmarkResult(
                fileName = tsvFile.name,
                measurements = measurements,
                stats = stats,
                success = true
            )

        } catch (e: Exception) {
            val totalElapsed = System.currentTimeMillis() - overallStartTime
            val stats = calculateStats(totalItemsProcessed, totalElapsed, measurements)

            return IngestionBenchmarkResult(
                fileName = tsvFile.name,
                measurements = measurements,
                stats = stats,
                success = false,
                errorMessage = e.message ?: e.javaClass.simpleName
            )
        }
    }

    private fun calculateStats(
        totalItems: Long,
        totalTimeMs: Long,
        measurements: List<IngestionMeasurement>
    ): IngestionStats {
        val overallItemsPerSecond = if (totalTimeMs > 0) {
            totalItems * 1000.0 / totalTimeMs
        } else {
            0.0
        }

        if (measurements.isEmpty()) {
            return IngestionStats(
                totalItems = totalItems,
                totalTimeMs = totalTimeMs,
                overallItemsPerSecond = overallItemsPerSecond,
                minItemsPerSecond = 0.0,
                maxItemsPerSecond = 0.0,
                meanItemsPerSecond = 0.0,
                medianItemsPerSecond = 0.0,
                stdDevItemsPerSecond = 0.0
            )
        }

        val rates = measurements.map { it.itemsPerSecond }
        val sortedRates = rates.sorted()
        val min = sortedRates.first()
        val max = sortedRates.last()
        val mean = rates.average()

        val median = if (sortedRates.size % 2 == 0) {
            (sortedRates[sortedRates.size / 2 - 1] + sortedRates[sortedRates.size / 2]) / 2.0
        } else {
            sortedRates[sortedRates.size / 2]
        }

        val variance = rates.map { (it - mean).pow(2) }.average()
        val stdDev = sqrt(variance)

        return IngestionStats(
            totalItems = totalItems,
            totalTimeMs = totalTimeMs,
            overallItemsPerSecond = overallItemsPerSecond,
            minItemsPerSecond = min,
            maxItemsPerSecond = max,
            meanItemsPerSecond = mean,
            medianItemsPerSecond = median,
            stdDevItemsPerSecond = stdDev
        )
    }

    private fun printResult(result: IngestionBenchmarkResult) {
        println()
        println("-".repeat(80))
        println("Results for: ${result.fileName}")
        println("-".repeat(80))

        if (!result.success) {
            println("  ERROR: ${result.errorMessage}")
            return
        }

        val stats = result.stats
        println("  Total items:           ${stats.totalItems}")
        println("  Total time:            ${stats.totalTimeMs} ms (${String.format("%.2f", stats.totalTimeMs / 1000.0)} s)")
        println("  Overall rate:          ${String.format("%.2f", stats.overallItemsPerSecond)} items/sec")
        println()
        println("  Rate statistics (per interval):")
        println("    Min:                 ${String.format("%.2f", stats.minItemsPerSecond)} items/sec")
        println("    Max:                 ${String.format("%.2f", stats.maxItemsPerSecond)} items/sec")
        println("    Mean:                ${String.format("%.2f", stats.meanItemsPerSecond)} items/sec")
        println("    Median:              ${String.format("%.2f", stats.medianItemsPerSecond)} items/sec")
        println("    Std Dev:             ${String.format("%.2f", stats.stdDevItemsPerSecond)} items/sec")
    }

    private fun generateMarkdownReport(result: IngestionBenchmarkResult): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

        return buildString {
            appendLine("# ${config.name} Report")
            appendLine()
            appendLine("Generated: $timestamp")
            appendLine()
            appendLine("## Configuration")
            appendLine()
            appendLine("| Parameter | Value |")
            appendLine("|-----------|-------|")
            appendLine("| TSV File | ${config.tsvFile} |")
            appendLine("| Database Host | ${config.dbHost} |")
            appendLine("| Database Port | ${config.dbPort} |")
            appendLine("| Database Name | ${config.dbName} |")
            appendLine("| Batch Size (& Measurement Interval) | ${config.batchSize} items |")
            appendLine("| Clear Before Run | ${config.clearBeforeRun} |")
            appendLine()

            if (!result.success) {
                appendLine("## Error")
                appendLine()
                appendLine("The benchmark failed with error: ${result.errorMessage}")
                return@buildString
            }

            val stats = result.stats
            appendLine("## Summary")
            appendLine()
            appendLine("| Metric | Value |")
            appendLine("|--------|-------|")
            appendLine("| Total Items | ${stats.totalItems} |")
            appendLine("| Total Time | ${stats.totalTimeMs} ms (${String.format("%.2f", stats.totalTimeMs / 1000.0)} s) |")
            appendLine("| Overall Rate | ${String.format("%.2f", stats.overallItemsPerSecond)} items/sec |")
            appendLine("| Min Rate | ${String.format("%.2f", stats.minItemsPerSecond)} items/sec |")
            appendLine("| Max Rate | ${String.format("%.2f", stats.maxItemsPerSecond)} items/sec |")
            appendLine("| Mean Rate | ${String.format("%.2f", stats.meanItemsPerSecond)} items/sec |")
            appendLine("| Median Rate | ${String.format("%.2f", stats.medianItemsPerSecond)} items/sec |")
            appendLine("| Std Dev | ${String.format("%.2f", stats.stdDevItemsPerSecond)} items/sec |")
            appendLine()

            appendLine("## Ingestion Progress")
            appendLine()
            appendLine("This table shows how ingestion performance changes as the database fills up.")
            appendLine()
            appendLine("| Items Processed | Elapsed Time (ms) | Interval Time (ms) | Items/Sec |")
            appendLine("|-----------------|-------------------|--------------------| ----------|")
            for (m in result.measurements) {
                appendLine("| ${m.itemsProcessed} | ${m.elapsedTimeMs} | ${m.intervalTimeMs} | ${String.format("%.2f", m.itemsPerSecond)} |")
            }
            appendLine()

            appendLine("## Performance Over Time")
            appendLine()
            appendLine("```")
            appendLine("Items Processed vs Items/Second")
            appendLine()

            // Simple ASCII chart
            if (result.measurements.isNotEmpty()) {
                val maxRate = result.measurements.maxOf { it.itemsPerSecond }
                val chartHeight = 10

                for (row in chartHeight downTo 1) {
                    val threshold = maxRate * row / chartHeight
                    val line = result.measurements.map { m ->
                        if (m.itemsPerSecond >= threshold) "█" else " "
                    }.joinToString("")
                    val label = String.format("%8.0f |", threshold)
                    appendLine("$label$line")
                }
                appendLine("         +" + "-".repeat(result.measurements.size))
                appendLine("          Items processed →")
            }
            appendLine("```")
        }
    }

    private fun saveReports(report: String, result: IngestionBenchmarkResult, timestamp: String) {
        val reportsDir = File(config.reportsDir)

        if (!reportsDir.exists()) {
            reportsDir.mkdirs()
        }

        // Save Markdown report
        val mdFile = File(reportsDir, "ingestion_benchmark_$timestamp.md")
        mdFile.writeText(report)
        println("Report saved to: ${mdFile.absolutePath}")

        // Save CSV report
        val csvFile = File(reportsDir, "ingestion_benchmark_$timestamp.csv")
        csvFile.writeText(buildString {
            appendLine("Items Processed,Elapsed Time (ms),Interval Time (ms),Items in Interval,Items/Sec")
            for (m in result.measurements) {
                appendLine("${m.itemsProcessed},${m.elapsedTimeMs},${m.intervalTimeMs},${m.itemsInInterval},${String.format("%.2f", m.itemsPerSecond)}")
            }
        })
        println("CSV saved to: ${csvFile.absolutePath}")

        // Save JSON report
        val jsonFile = File(reportsDir, "ingestion_benchmark_$timestamp.json")
        val jsonReport = mapOf(
            "benchmark" to config.name,
            "timestamp" to timestamp,
            "config" to mapOf(
                "tsvFile" to config.tsvFile,
                "dbHost" to config.dbHost,
                "dbPort" to config.dbPort,
                "dbName" to config.dbName,
                "batchSize" to config.batchSize,
                "clearBeforeRun" to config.clearBeforeRun,
                "skipLines" to config.skipLines
            ),
            "result" to mapOf(
                "fileName" to result.fileName,
                "success" to result.success,
                "errorMessage" to result.errorMessage,
                "stats" to mapOf(
                    "totalItems" to result.stats.totalItems,
                    "totalTimeMs" to result.stats.totalTimeMs,
                    "overallItemsPerSecond" to result.stats.overallItemsPerSecond,
                    "minItemsPerSecond" to result.stats.minItemsPerSecond,
                    "maxItemsPerSecond" to result.stats.maxItemsPerSecond,
                    "meanItemsPerSecond" to result.stats.meanItemsPerSecond,
                    "medianItemsPerSecond" to result.stats.medianItemsPerSecond,
                    "stdDevItemsPerSecond" to result.stats.stdDevItemsPerSecond
                ),
                "measurements" to result.measurements.map { m ->
                    mapOf(
                        "itemsProcessed" to m.itemsProcessed,
                        "elapsedTimeMs" to m.elapsedTimeMs,
                        "intervalTimeMs" to m.intervalTimeMs,
                        "itemsInInterval" to m.itemsInInterval,
                        "itemsPerSecond" to m.itemsPerSecond
                    )
                }
            )
        )
        jsonFile.writeText(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonReport))
        println("JSON saved to: ${jsonFile.absolutePath}")
    }
}




