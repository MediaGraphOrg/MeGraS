package org.megras.api.rest.handlers

import io.javalin.http.Context
import io.javalin.openapi.*
import org.megras.api.rest.GetRequestHandler
import org.megras.api.rest.RestErrorStatus
import org.megras.api.rest.data.sparql.ApiSparqlResult
import org.megras.api.rest.data.sparql.ApiSparqlResultValue
import org.megras.graphstore.QuadSet
import org.megras.lang.sparql.SparqlUtil
import org.slf4j.LoggerFactory

class SparqlQueryHandler(private val quads: QuadSet) : GetRequestHandler {

    private val logger = LoggerFactory.getLogger(this.javaClass)
    private val TIMING_ENABLED = false // Assuming this is set to true

    @OpenApi(
        summary = "Queries the Graph using SPARQL.",
        path = "/query/sparql",
        tags = ["Query"],
        operationId = OpenApiOperation.AUTO_GENERATE,
        methods = [HttpMethod.GET],
        queryParams = [
            OpenApiParam("query", String::class)
        ],
        responses = [
            OpenApiResponse("200", [OpenApiContent(ApiSparqlResult::class)]),
            OpenApiResponse("400", [OpenApiContent(RestErrorStatus::class)]),
            OpenApiResponse("404", [OpenApiContent(RestErrorStatus::class)]),
        ]
    )
    override fun get(ctx: Context) {

        val startTotal = if (TIMING_ENABLED) System.currentTimeMillis() else 0L
        val queryString = ctx.queryParam("query") ?: throw RestErrorStatus(400, "invalid query")

        // STEP 1 & 2: Core Execution
        val start2 = if (TIMING_ENABLED) System.currentTimeMillis() else 0L
        val table = SparqlUtil.select(queryString, quads)
        if (TIMING_ENABLED) logger.info("Handler Time spent in SparqlUtil.select: ${System.currentTimeMillis() - start2}ms")

        val start3 = if (TIMING_ENABLED) System.currentTimeMillis() else 0L

        // Build proper SPARQL JSON result with all rows
        val headers = table.headers.toList().map { it.removePrefix("?") }

        val jsonString = buildString {
            append("""{ "head": { "vars": [""")
            append(headers.joinToString(", ") { "\"$it\"" })
            append("] }, \"results\": { \"bindings\": [")

            // Build ALL binding entries
            table.rows.forEachIndexed { index, row ->
                if (index > 0) append(", ")
                append("{")
                val bindingsMap = row.mapKeys { it.key.removePrefix("?") }
                    .mapValues { ApiSparqlResultValue.fromQuadValue(it.value) }
                append(bindingsMap.entries.joinToString(", ") { (key, value) ->
                    // Build the inner SPARQL Result Value format: {"value":"...", "type":"...", "datatype":"..."}
                    val datatypePart = value.datatype?.let { """, "datatype": "$it"""" } ?: ""
                    """"$key": { "value": "${escapeJsonString(value.value)}", "type": "${value.type}"$datatypePart }"""
                })
                append("}")
            }
            append("] } }")
        }

        // Use the low-level result method
        ctx.contentType("application/json")
        ctx.result(jsonString)

        if (TIMING_ENABLED) logger.info("Handler Time spent in **Manual JSON String Building** (ctx.result): ${System.currentTimeMillis() - start3}ms")

        if (TIMING_ENABLED) logger.info("Total time spent in SparqlQueryHandler.get: ${System.currentTimeMillis() - startTotal}ms")
    }

    /**
     * Escapes special characters in a string for safe JSON embedding.
     */
    private fun escapeJsonString(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}