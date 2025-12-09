package org.megras.api.rest.handlers

import io.javalin.http.Context
import io.javalin.openapi.*
import org.megras.api.rest.GetRequestHandler
import org.megras.api.rest.RestErrorStatus
import org.megras.api.rest.data.sparql.ApiSparqlResult
import org.megras.graphstore.QuadSet
import org.megras.lang.sparql.SparqlUtil
import org.slf4j.LoggerFactory // <-- ADD THIS IMPORT

class SparqlQueryHandler(private val quads: QuadSet) : GetRequestHandler {

    private val logger = LoggerFactory.getLogger(this.javaClass)

    // TOGGLE SWITCH: Set to 'false' to disable timing completely for production
    private val TIMING_ENABLED = true


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

        // STEP 1: Request parsing and validation
        val start1 = if (TIMING_ENABLED) System.currentTimeMillis() else 0L
        val queryString = ctx.queryParam("query") ?: throw RestErrorStatus(400, "invalid query")
        if (TIMING_ENABLED) logger.info("Handler Time spent in Request Parsing (queryParam): ${System.currentTimeMillis() - start1}ms")

        // STEP 2: Core SPARQL Execution (This logs 158ms internally)
        val start2 = if (TIMING_ENABLED) System.currentTimeMillis() else 0L
        val table = SparqlUtil.select(queryString, quads)
        if (TIMING_ENABLED) logger.info("Handler Time spent in SparqlUtil.select: ${System.currentTimeMillis() - start2}ms")

        // STEP 3: Result Serialization (THE PRIME SUSPECT FOR THE 380ms DELAY)
        val start3 = if (TIMING_ENABLED) System.currentTimeMillis() else 0L
        ctx.json(ApiSparqlResult(table))
        if (TIMING_ENABLED) logger.info("Handler Time spent in Result Serialization (ctx.json): ${System.currentTimeMillis() - start3}ms")

        if (TIMING_ENABLED) logger.info("Total time spent in SparqlQueryHandler.get: ${System.currentTimeMillis() - startTotal}ms")
    }
}