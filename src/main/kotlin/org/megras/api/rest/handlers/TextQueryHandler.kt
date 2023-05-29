package org.megras.api.rest.handlers

import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import io.javalin.openapi.*
import org.megras.api.rest.PostRequestHandler
import org.megras.api.rest.RestErrorStatus
import org.megras.api.rest.data.ApiQuad
import org.megras.api.rest.data.ApiQueryResult
import org.megras.api.rest.data.ApiTextQuery
import org.megras.data.graph.QuadValue
import org.megras.graphstore.QuadSet


class TextQueryHandler(private val quads: QuadSet) : PostRequestHandler {

    @OpenApi(
        summary = "Queries the Graph for quads with a specific predicate and an object string that matches a specified filter text.",
        path = "/query/text",
        tags = ["Query"],
        operationId = OpenApiOperation.AUTO_GENERATE,
        methods = [HttpMethod.POST],
        requestBody = OpenApiRequestBody([OpenApiContent(ApiTextQuery::class)]),
        responses = [
            OpenApiResponse("200", [OpenApiContent(ApiQueryResult::class)]),
            OpenApiResponse("400", [OpenApiContent(RestErrorStatus::class)]),
            OpenApiResponse("404", [OpenApiContent(RestErrorStatus::class)]),
        ]
    )
    override fun post(ctx: Context) {

        val query = try {
            ctx.bodyAsClass(ApiTextQuery::class.java)
        } catch (e: BadRequestResponse) {
            throw RestErrorStatus(400, "invalid query")
        }

        val results = quads.textFilter(
            QuadValue.of(query.predicate),
            query.filterText
        ).map { ApiQuad(it) }

        ctx.json(ApiQueryResult(results))
    }
}