package org.megras.api.rest.handlers

import io.javalin.http.Context
import io.javalin.openapi.*
import org.megras.api.rest.GetRequestHandler
import org.megras.api.rest.RestErrorStatus
import org.megras.api.rest.data.sparql.ApiSparqlResult
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

        // Serialize through Jackson (via ctx.json) rather than hand-building the
        // JSON string; this guarantees correct escaping of control characters
        // and avoids a latent injection/breakout surface in result values.
        ctx.json(ApiSparqlResult(table))

        if (TIMING_ENABLED) logger.info("Total time spent in SparqlQueryHandler.get: ${System.currentTimeMillis() - startTotal}ms")
    }
}