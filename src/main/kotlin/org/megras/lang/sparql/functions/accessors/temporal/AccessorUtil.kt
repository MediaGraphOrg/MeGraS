package org.megras.lang.sparql.functions.accessors.temporal

import org.megras.data.graph.QuadValue
import org.megras.data.graph.StringValue
import org.megras.data.graph.TemporalValue
import org.megras.data.graph.URIValue
import org.megras.graphstore.MutableQuadSet

object AccessorUtil {
    internal fun getDateTime(subject: URIValue, quadSet: MutableQuadSet, predicates: Collection<QuadValue>): TemporalValue? {
        val subjectQuads = quadSet.filter(listOf(subject), null, null)
        if (subjectQuads.isEmpty()) {
            throw IllegalArgumentException("No data found for subject")
        }

        // Look for predicates related to time
        val timeQuads = subjectQuads.filter(null, predicates, null)
        if (timeQuads.isNotEmpty()) {
            // Extract the time value
            val timeValue = timeQuads.first().`object`

            // Check if the time value is a string
            if (timeValue is StringValue) {
                // Parse the string to a date
                return TemporalValue(timeValue.value)
            } else if (timeValue is URIValue) {
                // If it's a URI, we can try to convert it to a date
                return TemporalValue(timeValue.value)
            } else {
                throw IllegalArgumentException("Unsupported time value type")
            }
        }

        // If we got here, we didn't find any recognized time
        //throw IllegalArgumentException("No time found for subject")
        return null
    }
}