package org.megras.api.rest.handlers

import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import io.javalin.openapi.*
import org.megras.api.rest.PostRequestHandler
import org.megras.api.rest.RestErrorStatus
import org.megras.api.rest.data.ApiBasicQuery
import org.megras.api.rest.data.ApiPathQuery
import org.megras.api.rest.data.ApiQuad
import org.megras.api.rest.data.ApiQueryResult
import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.graphstore.QuadSet

class PathQueryHandler(private val quads: QuadSet) : PostRequestHandler {

    @OpenApi(
        summary = "Queries a path along a set of predicates starting from a set of subjects.",
        path = "/query/path",
        tags = ["Query"],
        operationId = OpenApiOperation.AUTO_GENERATE,
        methods = [HttpMethod.POST],
        requestBody = OpenApiRequestBody([OpenApiContent(ApiPathQuery::class)]),
        responses = [
            OpenApiResponse("200", [OpenApiContent(ApiQueryResult::class)]),
            OpenApiResponse("400", [OpenApiContent(RestErrorStatus::class)]),
            OpenApiResponse("404", [OpenApiContent(RestErrorStatus::class)]),
        ]
    )
    override fun post(ctx: Context) {

        val query = try {
            ctx.bodyAsClass(ApiPathQuery::class.java)
        } catch (e: BadRequestResponse) {
            throw RestErrorStatus(400, "invalid query")
        }

        val seeds = query.seeds?.mapNotNull { if (it != null) QuadValue.of(it) else null } ?: throw RestErrorStatus(400, "invalid query")
        val predicates = query.predicates?.mapNotNull { if (it != null) QuadValue.of(it) else null } ?: throw RestErrorStatus(400, "invalid query")
        val maxDepth = if(query.maxDepth > 0) query.maxDepth else Int.MAX_VALUE

        val results = mutableSetOf<Quad>()

        var iteration = 0
        var start = seeds.toSet()

        while (iteration++ < maxDepth) {

            val step = quads.filter(start, predicates, null)

            if (step.isEmpty()) {
                break
            }

            results.addAll(step)
            start = step.map { it.`object` }.toSet()


        }

        ctx.json(ApiQueryResult(results.map { ApiQuad(it) }))
    }
}