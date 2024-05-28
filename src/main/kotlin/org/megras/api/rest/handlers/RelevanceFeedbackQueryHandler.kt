package org.megras.api.rest.handlers

import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import io.javalin.openapi.*
import libsvm.svm
import libsvm.svm_parameter
import libsvm.svm_problem
import org.megras.api.rest.PostRequestHandler
import org.megras.api.rest.RestErrorStatus
import org.megras.api.rest.data.ApiQuad
import org.megras.api.rest.data.ApiQueryResult
import org.megras.api.rest.data.ApiRelevanceFeedbackQuery
import org.megras.data.graph.DoubleVectorValue
import org.megras.data.graph.QuadValue
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

        val predicate = QuadValue.of(query.predicate)
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

        var x = positives.map {
            DoubleVectorValue.parse(it.o).vector
        }.plus(
            negatives.map {
                DoubleVectorValue.parse(it.o).vector
            }
        ).toTypedArray()
        var y = IntArray(positives.size){1}.plus(IntArray(negatives.size){-1})

        // train an SVM with libSVM on x, y
        // and extract hyperplane from the trained SVM and store it in `object`
        val prob = svm_problem().apply {
            l = x.size
            y = y
            x = x
        }

        // Set up the SVM parameters
        val param = svm_parameter().apply {
            svm_type = svm_parameter.C_SVC
            kernel_type = svm_parameter.LINEAR
            degree = 3
            gamma = 0.0
            coef0 = 0.0
            nu = 0.5
            cache_size = 100.0
            C = 1.0
            eps = 1e-3
            p = 0.1
            shrinking = 1
            probability = 0
            nr_weight = 0
            weight_label = IntArray(0)
            weight = DoubleArray(0)
        }

        // Train the SVM model
        val model = svm.svm_train(prob, param)

        // Extract the hyperplane (normal vector of the hyperplane)
        val `object` = DoubleVectorValue(model.sv_coef[0])

        val results = quads.farthestNeighbor(
            predicate,
            `object`,
            count,
            distance
        ).map { ApiQuad(it) }

        ctx.json(ApiQueryResult(results))


    }
}
