package org.megras.lang.sparql.functions.accessors.temporal

import org.apache.jena.sparql.expr.NodeValue
import org.apache.jena.sparql.function.FunctionBase1
import org.megras.graphstore.MutableQuadSet
import org.megras.lang.sparql.SparqlUtil
import org.apache.jena.datatypes.xsd.XSDDateTime
import org.megras.data.graph.URIValue

class EndAccessor : FunctionBase1() {

    companion object {
        private lateinit var quadSet: MutableQuadSet

        // Common predicates for end times across different media types
        private val END_TIME_PREDICATES = setOf(
            URIValue("http://purl.org/dc/terms/modified"),
            URIValue("http://www.w3.org/2006/time#hasEnd"),
            URIValue("http://schema.org/endDate"),
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

        return getEnd(subjectQuads)
    }

    internal fun getEnd(subjectQuads: MutableQuadSet): NodeValue {
        if (subjectQuads.isEmpty()) {
            throw IllegalArgumentException("No data found for subject")
        }

        // Look for predicates related to end time
        val endTimeQuads = subjectQuads.filter(null, END_TIME_PREDICATES, null)
        if (endTimeQuads.isNotEmpty()) {
            // Extract the end time value
            val endTimeValue = endTimeQuads.first().`object`

            // Try to parse the time value based on its type
            return try {
                when (endTimeValue) {
                    // If it's already an XSDDateTime, use it directly
                    is XSDDateTime -> NodeValue.makeDateTime(endTimeValue.toString())
                    // Otherwise try to parse the string representation using common formats
                    else -> ParseUtil.parseDateTimeString(endTimeValue.toString())
                }
            } catch (e: Exception) {
                throw IllegalArgumentException("Failed to parse end time")
            }
        }

        // If we got here, we didn't find any recognized end time
        throw IllegalArgumentException("No end time found for subject")
    }
}