package org.megras.api.rest.data.sparql

import org.megras.lang.ResultTable

/**
 * Basic implementation of https://www.w3.org/TR/sparql11-results-json/
 */
data class ApiSparqlResult(val head: ApiSparqlResultHead, val results: ApiSparqlResultBody) {

    constructor(resultTable: ResultTable) : this(
        ApiSparqlResultHead(resultTable.headers.toList()),
        ApiSparqlResultBody(resultTable.rows.map { m ->
            m.map { it.key to ApiSparqlResultValue.fromQuadValue(it.value) }.toMap()
        })
    )

}
