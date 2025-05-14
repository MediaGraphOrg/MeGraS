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

    internal fun getStart(subjectQuads: MutableQuadSet): NodeValue {
        if (subjectQuads.isEmpty()) {
            throw IllegalArgumentException("No data found for subject")
        }

        // Look for predicates related to start time
        val startTimeQuads = subjectQuads.filter(null, START_TIME_PREDICATES, null)
        if (startTimeQuads.isNotEmpty()) {
            // Extract the start time value
            val startTimeValue = startTimeQuads.first().`object`

            // Try to parse the time value based on its type
            return try {
                when (startTimeValue) {
                    // If it's already an XSDDateTime, use it directly
                    is XSDDateTime -> NodeValue.makeDateTime(startTimeValue.toString())
                    // Otherwise try to parse the string representation using common formats
                    else -> ParseUtil.parseDateTimeString(startTimeValue.toString())
                }
            } catch (e: Exception) {
                throw IllegalArgumentException("Failed to parse start time")
            }
        }

        // If we got here, we didn't find any recognized start time
        throw IllegalArgumentException("No start time found for subject")
    }
}