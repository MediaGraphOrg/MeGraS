package org.megras.benchmark.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Centralized report generation for all benchmark types.
 * Generates reports in Markdown, CSV, and JSON formats.
 */
object BenchmarkReportGenerator {

    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    /**
     * Generates a timestamp string for report filenames.
     */
    fun generateTimestamp(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))

    /**
     * Generates a human-readable timestamp for report content.
     */
    fun generateReadableTimestamp(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

    /**
     * Ensures the reports directory exists and returns it.
     */
    fun ensureReportsDir(reportsDir: String): File {
        return File(reportsDir).also {
            if (!it.exists()) it.mkdirs()
        }
    }

    /**
     * Saves content to a file in the reports directory.
     */
    fun saveReport(reportsDir: File, filename: String, content: String): File {
        val file = File(reportsDir, filename)
        file.writeText(content)
        println("Report saved to: ${file.absolutePath}")
        return file
    }

    /**
     * Saves a JSON report.
     */
    fun saveJsonReport(reportsDir: File, filename: String, data: Any): File {
        val file = File(reportsDir, filename)
        file.writeText(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data))
        println("JSON saved to: ${file.absolutePath}")
        return file
    }

    /**
     * Generates a summary table row for a benchmark result.
     */
    fun formatLatencyRow(
        label: String,
        stats: BenchmarkStatistics.LatencyStats,
        resultCount: Int? = null,
        coldStartMs: Long? = null
    ): String {
        return buildString {
            append("| $label | ")
            append("${resultCount ?: "-"} | ")
            append("${coldStartMs ?: "-"} | ")
            append("${stats.minMs} | ")
            append("${stats.maxMs} | ")
            append("${BenchmarkStatistics.formatMs(stats.meanMs)} | ")
            append("${BenchmarkStatistics.formatMs(stats.medianMs)} | ")
            append("${BenchmarkStatistics.formatMs(stats.stdDevMs)} | ")
            append("${stats.successfulRuns}/${stats.totalRuns} |")
        }
    }

    /**
     * Generates a Markdown table header for latency results.
     */
    fun latencyTableHeader(): String = buildString {
        appendLine("| Query | Results | Cold Start (ms) | Min (ms) | Max (ms) | Mean (ms) | Median (ms) | Std Dev (ms) | Success Rate |")
        append("|-------|---------|-----------------|----------|----------|-----------|-------------|--------------|--------------|")
    }

    /**
     * Generates configuration section for a report.
     */
    fun generateConfigSection(configMap: Map<String, Any?>): String = buildString {
        appendLine("## Configuration")
        appendLine()
        appendLine("| Parameter | Value |")
        appendLine("|-----------|-------|")
        configMap.forEach { (key, value) ->
            appendLine("| $key | $value |")
        }
    }

    /**
     * Generates a standard report header.
     */
    fun generateReportHeader(title: String, description: String? = null): String = buildString {
        appendLine("# $title")
        appendLine()
        appendLine("Generated: ${generateReadableTimestamp()}")
        if (description != null) {
            appendLine()
            appendLine(description)
        }
        appendLine()
    }

    /**
     * Helper to convert data to JSON string.
     */
    fun toJson(data: Any): String = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data)
}

