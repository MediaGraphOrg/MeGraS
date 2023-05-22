package org.megras.api.rest.handlers

import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import io.javalin.openapi.*
import org.megras.api.rest.PostRequestHandler
import org.megras.api.rest.RestErrorStatus
import org.megras.api.rest.data.ApiQuad
import org.megras.api.rest.data.ApiQueryResult
import org.megras.api.rest.data.ApiKnnQuery
import org.megras.data.graph.QuadValue
import org.megras.graphstore.Distance
import org.megras.graphstore.QuadSet

class KnnQueryHandler(private val quads: QuadSet) : PostRequestHandler {

    @OpenApi(
        summary = "Queries the Graph for quads within a kNN-cluster.",
        path = "/query/knn",
        tags = ["Query"],
        operationId = OpenApiOperation.AUTO_GENERATE,
        methods = [HttpMethod.POST],
        requestBody = OpenApiRequestBody([OpenApiContent(ApiKnnQuery::class)]),
        responses = [
            OpenApiResponse("200", [OpenApiContent(ApiQueryResult::class)]),
            OpenApiResponse("400", [OpenApiContent(RestErrorStatus::class)]),
            OpenApiResponse("404", [OpenApiContent(RestErrorStatus::class)]),
        ]
    )
    override fun post(ctx: Context) {

        val query = try {
            ctx.bodyAsClass(ApiKnnQuery::class.java)
        } catch (e: BadRequestResponse) {
            throw RestErrorStatus(400, "invalid query")
        }

        val predicate = QuadValue.of(query.predicate)
        val `object` = QuadValue.of(query.`object`)
        val count = query.count
        if (count < 1) {
            throw RestErrorStatus(400, "invalid query: count smaller than one")
        }
        val distance = Distance.valueOf(query.distance.toString())

        val results = quads.nearestNeighbor(
            predicate,
            `object`,
            count,
            distance
        ).map { ApiQuad(it) }

        ctx.json(ApiQueryResult(results))
    }
}