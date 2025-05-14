package org.megras.lang.sparql.functions.accessors.temporal

import org.apache.jena.sparql.expr.NodeValue

class ParseUtil {
    companion object {
        internal fun parseDateTimeString(timeStr: String): NodeValue {
            // Drop the ^^String, if present
            val cleanTimeStr = timeStr.substringBefore("^^").trim()
            // Try different date formats based on expected inputs
            return try {
                // Try ISO-8601 format first (most common)
                if (cleanTimeStr.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*"))) {
                    NodeValue.makeDateTime(cleanTimeStr)
                }
                // Try 2019-01-02 09:20:29 (common in some databases)
                else if (cleanTimeStr.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"))) {
                    NodeValue.makeDateTime(cleanTimeStr.replace(" ", "T"))
                }
                // Try date only format
                else if (cleanTimeStr.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                    NodeValue.makeDate(cleanTimeStr)
                }
                // Try Unix timestamp (assuming milliseconds)
                else if (cleanTimeStr.matches(Regex("\\d+"))) {
                    val instant = java.time.Instant.ofEpochMilli(cleanTimeStr.toLong())
                    val dateTime = java.time.format.DateTimeFormatter.ISO_INSTANT.format(instant)
                    NodeValue.makeDateTime(dateTime)
                }
                // Default attempt with Jena's parsing
                else {
                    NodeValue.makeDateTime(cleanTimeStr)
                }
            } catch (e: Exception) {
                throw IllegalArgumentException("Unsupported time format")
            }
        }
    }
}