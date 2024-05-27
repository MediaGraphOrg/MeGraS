package org.megras.api.rest.handlers

import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import io.javalin.openapi.*
import org.megras.api.rest.PostRequestHandler
import org.megras.api.rest.RestErrorStatus
import org.megras.api.rest.data.ApiQuad
import org.megras.api.rest.data.ApiQueryResult
import org.megras.api.rest.data.ApiRelevanceFeedbackQuery
import org.megras.data.graph.DoubleVectorValue
import org.megras.data.graph.QuadValue
import org.megras.data.graph.VectorValue
import org.megras.graphstore.Distance
import org.megras.graphstore.QuadSet


class RelevanceFeedbackQueryHandler(private val quads: QuadSet) : PostRequestHandler {

    @OpenApi(
        summary = "Queries the Graph for quads based on positive and negative examples.",
        path = "/query/relevancefeedback",
        tags = ["Query"],
        operationId = OpenApiOperation.AUTO_GENERATE,
        methods = [HttpMethod.POST],
        requestBody = OpenApiRequestBody([OpenApiContent(ApiRelevanceFeedbackQuery::class)]),
        responses = [
            OpenApiResponse("200", [OpenApiContent(ApiQueryResult::class)]),
            OpenApiResponse("400", [OpenApiContent(RestErrorStatus::class)]),
            OpenApiResponse("404", [OpenApiContent(RestErrorStatus::class)]),
        ]
    )
    override fun post(ctx: Context) {

        val query = try {
            ctx.bodyAsClass(ApiRelevanceFeedbackQuery::class.java)
        } catch (e: BadRequestResponse) {
            throw RestErrorStatus(400, "invalid query")
        }

        val positives = quads.filter(
            subjects = query.positives.map{QuadValue.of(it)},
            predicates = query.predicate.map{QuadValue.of(it)},
            objects = null
        ).map { ApiQuad(it) }
        val negatives = quads.filter(
            subjects = query.negatives.map{QuadValue.of(it)},
            predicates = query.predicate.map{QuadValue.of(it)},
            objects = null
        ).map { ApiQuad(it) }
        val count = query.count
        if (count < 1) {
            throw RestErrorStatus(400, "invalid query: count smaller than one")
        }
        val distance = Distance.valueOf(query.distance.toString())

        // TODO: SVM

        /*val `object` = DoubleVectorValue() // normal vector of hyperplane

        val results = quads.farthestNeighbor(
            predicate,
            `object`,
            count,
            distance
        ).map { ApiQuad(it) }

        ctx.json(ApiQueryResult(results))*/
    }
}
