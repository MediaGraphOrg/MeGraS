package org.megras.api.rest.handlers

import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import io.javalin.openapi.*
import org.megras.api.rest.PostRequestHandler
import org.megras.api.rest.RestErrorStatus
import org.megras.api.rest.data.ApiQuad
import org.megras.api.rest.data.ApiQueryResult
import org.megras.api.rest.data.ApiRelevanceFeedbackQuery
import org.megras.data.graph.QuadValue
import org.megras.graphstore.MutableQuadSet


class RelevanceFeedbackQueryHandler(val positives: MutableQuadSet, val negatives: MutableQuadSet) : PostRequestHandler {

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

        val positives = QuadValue.of(query.positives)
        val negatives = QuadValue.of(query.negatives)

        // TODO: SVM

        //val results =

        //ctx.json(ApiQueryResult(results))
    }
}
