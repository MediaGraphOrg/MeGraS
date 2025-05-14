package org.megras.lang.sparql.functions.accessors.temporal

import org.apache.jena.datatypes.xsd.XSDDateTime
import org.apache.jena.sparql.expr.NodeValue
import org.megras.data.graph.QuadValue
import org.megras.data.graph.URIValue
import org.megras.graphstore.MutableQuadSet

object ParseUtil {
    private fun parseDateTimeString(timeStr: String): NodeValue {
        // Drop the ^^String, if present
        val cleanTimeStr = timeStr.substringBefore("^^").trim()
        // Try different date formats based on expected inputs
        return try {
            // Try ISO-8601 format first (most common)
            if (cleanTimeStr.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*"))) {
                NodeValue.makeDateTime(cleanTimeStr)
            }
            // Try YYYY-MM-DD HH:MM:SS (common in some databases)
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

    internal fun getDateTime(subject: URIValue, quadSet: MutableQuadSet, predicates: Collection<QuadValue>): NodeValue {
        val subjectQuads = quadSet.filterSubject(subject) as MutableQuadSet
        if (subjectQuads.isEmpty()) {
            throw IllegalArgumentException("No data found for subject")
        }

        // Look for predicates related to time
        val timeQuads = subjectQuads.filter(null, predicates, null)
        if (timeQuads.isNotEmpty()) {
            // Extract the time value
            val timeValue = timeQuads.first().`object`

            // Try to parse the time value based on its type
            return try {
                when (timeValue) {
                    // If it's already an XSDDateTime, use it directly
                    is XSDDateTime -> NodeValue.makeDateTime(timeValue.toString())
                    // Otherwise try to parse the string representation using common formats
                    else -> parseDateTimeString(timeValue.toString())
                }
            } catch (e: Exception) {
                throw IllegalArgumentException("Failed to parse time")
            }
        }

        // If we got here, we didn't find any recognized time
        throw IllegalArgumentException("No time found for subject")
    }
}