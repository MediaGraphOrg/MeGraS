package org.megras.api.rest.data.sparql

import org.megras.data.graph.*

data class ApiSparqlResultValue(val value: String, val type: String, val datatype: String? = null) {

    companion object {

        fun fromQuadValue(value: QuadValue): ApiSparqlResultValue = when(value) {
            is DoubleValue -> ApiSparqlResultValue("${value.value}", "literal") //TODO type
            is LongValue -> ApiSparqlResultValue("${value.value}", "literal") //TODO type
            is StringValue -> ApiSparqlResultValue("${value.value}", "literal") //TODO type
            is URIValue -> ApiSparqlResultValue(value.value, "uri")
            is VectorValue -> TODO()
        }

    }

}
