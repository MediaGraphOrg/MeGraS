package org.megras.lang.sparql.functions.accessors.temporal

import org.apache.jena.sparql.expr.NodeValue
import org.apache.jena.sparql.function.FunctionBase1
import org.megras.graphstore.MutableQuadSet
import org.megras.lang.sparql.SparqlUtil
import org.apache.jena.datatypes.xsd.XSDDateTime
import org.megras.data.graph.URIValue

class StartAccessor : FunctionBase1() {

    companion object {
        private lateinit var quadSet: MutableQuadSet

        // Common predicates for start times across different media types
        private val START_TIME_PREDICATES = setOf(
            URIValue("http://purl.org/dc/terms/created"),
            URIValue("http://www.w3.org/2006/time#hasBeginning"),
            URIValue("http://schema.org/startDate"),
            URIValue("http://lsc.dcu.ie/schema#utc_time")
            // Add more predicates as needed
        )

        fun setQuads(quadSet: MutableQuadSet) {
            this.quadSet = quadSet
        }
    }

    override fun exec(arg: NodeValue): NodeValue {
        // Get the subject from the argument
        val subject = SparqlUtil.toQuadValue(arg.asNode())
            ?: throw IllegalArgumentException("Invalid subject")

        // Find all quads with the given subject
        val subjectQuads = quadSet.filterSubject(subject) as MutableQuadSet

        return getStart(subjectQuads)
    }

    private fun parseTimeString(timeStr: String): NodeValue {
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

    internal fun getStart(subjectQuads: MutableQuadSet): NodeValue {
        if (subjectQuads.isEmpty()) {
            throw IllegalArgumentException("No data found for subject")
        }

        // Look for predicates related to start time
        for (predicate in START_TIME_PREDICATES) {
            val startTimeQuads = subjectQuads.filterPredicate(predicate)
            if (startTimeQuads.isNotEmpty()) {
                // Extract the start time value
                val startTimeValue = startTimeQuads.first().`object`

                // Try to parse the time value based on its type
                return try {
                    when (startTimeValue) {
                        // If it's already an XSDDateTime, use it directly
                        is XSDDateTime -> NodeValue.makeDateTime(startTimeValue.toString())
                        // Otherwise try to parse the string representation using common formats
                        else -> parseTimeString(startTimeValue.toString())
                    }
                } catch (e: Exception) {
                    throw IllegalArgumentException("Failed to parse start time: ${e.message}")
                }
            }
        }

        // If we got here, we didn't find any recognized start time
        throw IllegalArgumentException("No start time found for subject")
    }
}