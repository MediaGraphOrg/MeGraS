package org.megras.api.rest.data.sparql

import org.megras.data.graph.*
import java.time.format.DateTimeFormatter // <-- CRITICAL: Ensure this import is present

data class ApiSparqlResultValue(val value: String, val type: String, val datatype: String? = null) {

    companion object {

        // Use standard XSD URIs for SPARQL Results JSON format
        private const val XSD_LONG = "http://www.w3.org/2001/XMLSchema#long"
        private const val XSD_DOUBLE = "http://www.w3.org/2001/XMLSchema#double"
        private const val XSD_STRING = "http://www.w3.org/2001/XMLSchema#string"
        private const val XSD_DATETIME = "http://www.w3.org/2001/XMLSchema#dateTime"

        /**
         * Converts a QuadValue to the serializable ApiSparqlResultValue.
         */
        fun fromQuadValue(value: QuadValue): ApiSparqlResultValue = when(value) {

            // Numeric Literals
            is DoubleValue -> ApiSparqlResultValue("${value.value}", "literal", XSD_DOUBLE)
            is LongValue -> ApiSparqlResultValue("${value.value}", "literal", XSD_LONG)

            // String Literal
            is StringValue -> ApiSparqlResultValue("${value.value}", "literal", XSD_STRING)

            // URI
            is URIValue -> ApiSparqlResultValue(value.value, "uri")
            is TemporalValue -> ApiSparqlResultValue(
                value.dateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "literal",
                XSD_DATETIME
            )

            // Vector: Fallback to toString(), but we use XSD_STRING for compliance
            is VectorValue -> ApiSparqlResultValue(value.toString(), "literal", XSD_STRING)
        }
    }

}