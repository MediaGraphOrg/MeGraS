package org.megras.api.rest.data.sparql

import org.megras.data.graph.*

data class ApiSparqlResultValue(val value: String, val type: String, val datatype: String? = null) {

    companion object {

        // Use standard XSD URIs for SPARQL Results JSON format
        private const val XSD_LONG = "http://www.w3.org/2001/XMLSchema#long"
        private const val XSD_DOUBLE = "http://www.w3.org/2001/XMLSchema#double"
        private const val XSD_STRING = "http://www.w3.org/2001/XMLSchema#string"
        private const val XSD_DATETIME = "http://www.w3.org/2001/XMLSchema#dateTime"

        /**
         * Converts a QuadValue to the serializable ApiSparqlResultValue.
         * Uses explicit properties (.value) and hardcoded datatypes to minimize reflection and maximize speed.
         */
        fun fromQuadValue(value: QuadValue): ApiSparqlResultValue = when(value) {

            // Numeric Literals
            is DoubleValue -> ApiSparqlResultValue("${value.value}", "literal", XSD_DOUBLE)
            is LongValue -> ApiSparqlResultValue("${value.value}", "literal", XSD_LONG)

            // String Literals
            is StringValue -> ApiSparqlResultValue("${value.value}", "literal", XSD_STRING)

            // URIs
            is URIValue -> ApiSparqlResultValue(value.value, "uri") // value.value is assumed to hold the full URI string

            // Complex Types (Using toString() is a fallback for data extraction)
            is TemporalValue -> ApiSparqlResultValue(value.toString(), "literal", XSD_DATETIME)
            is VectorValue -> ApiSparqlResultValue(value.toString(), "literal")
        }
    }

}