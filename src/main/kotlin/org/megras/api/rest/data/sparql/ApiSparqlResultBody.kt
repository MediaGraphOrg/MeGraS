package org.megras.api.rest.data.sparql

data class ApiSparqlResultBody(val bindings: List<Map<String, ApiSparqlResultValue>>)