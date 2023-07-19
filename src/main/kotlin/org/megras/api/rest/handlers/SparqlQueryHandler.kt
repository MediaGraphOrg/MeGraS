package org.megras.api.rest.handlers

import io.javalin.http.Context
import io.javalin.openapi.*
import org.megras.api.rest.GetRequestHandler
import org.megras.api.rest.RestErrorStatus
import org.megras.api.rest.data.sparql.ApiSparqlResult
import org.megras.graphstore.QuadSet
import org.megras.lang.sparql.SparqlUtil

class SparqlQueryHandler(private val quads: QuadSet) : GetRequestHandler {

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

        val queryString = ctx.queryParam("query") ?: throw RestErrorStatus(400, "invalid query")

        val table = SparqlUtil.select(queryString, quads)

        ctx.json(ApiSparqlResult(table))
    }


}