package org.megras.api.rest.handlers

import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import io.javalin.openapi.*
import libsvm.svm
import libsvm.svm_node
import libsvm.svm_parameter
import libsvm.svm_problem
import org.megras.api.rest.PostRequestHandler
import org.megras.api.rest.RestErrorStatus
import org.megras.api.rest.data.ApiQuad
import org.megras.api.rest.data.ApiQueryResult
import org.megras.api.rest.data.ApiRelevanceFeedbackQuery
import org.megras.data.graph.*
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

        if (query.count < 1) {
            throw RestErrorStatus(400, "invalid query: count smaller than one")
        }

        val predicate = QuadValue.of(query.predicate)

        val positiveIds = query.positives.map { QuadValue.of(it) }.toSet()
        val negativeIds = query.negatives.map { QuadValue.of(it) }.toSet()

        val queryQuads = quads.filter(
            subjects = positiveIds + negativeIds,
            predicates = setOf(predicate),
            objects = null
        )

        val positives = toSVMNodes(queryQuads.filter(subjects = positiveIds, predicates = null, objects = null))
        val negatives = toSVMNodes(queryQuads.filter(subjects = negativeIds, predicates = null, objects = null))

        val x = positives + negatives
        val y = DoubleArray(positives.size) { 1.0 } + DoubleArray(negatives.size) { 2.0 }


        // train an SVM with libSVM on x, y
        // and extract hyperplane from the trained SVM and store it in hyperplane
        val prob = svm_problem().apply {
            this.l = x.size
            this.y = y
            this.x = x
        }

        // Set up the SVM parameters
        val param = svm_parameter().apply {
            svm_type = svm_parameter.C_SVC
            kernel_type = svm_parameter.LINEAR
            degree = positives[0].size - 1
            gamma = 0.0
            coef0 = 0.0
            nu = 0.5
            cache_size = 100.0
            C = 1.0
            eps = 1e-3
            p = 0.1
            shrinking = 0
            probability = 1
            nr_weight = 0
        }

        // Train the SVM model
        val model = svm.svm_train(prob, param)

        // Extract the hyperplane (normal vector of the hyperplane)
        val hyperplane = FloatVectorValue(model.sv_coef[0])

        val results = quads.nearestNeighbor(
            predicate,
            hyperplane,
            query.count,
            Distance.DOTPRODUCT,
            true
        ).map { ApiQuad(it) }

        ctx.json(ApiQueryResult(results))


    }

    private fun toSVMNodes(quads: QuadSet): Array<Array<svm_node>> = quads.mapNotNull { quad ->
        when (quad.`object` as? VectorValue) {
            is DoubleVectorValue -> {
                val list = (quad.`object` as DoubleVectorValue).vector.mapIndexed { index, d ->
                    svm_node().also { node ->
                        node.index = index + 1
                        node.value = d
                    }
                }.toMutableList()
                list.add(svm_node().also { it.index = -1 })
                list.toTypedArray()
            }

            is FloatVectorValue -> {
                val list = (quad.`object` as FloatVectorValue).vector.mapIndexed { index, d ->
                    svm_node().also { node ->
                        node.index = index + 1
                        node.value = d.toDouble()
                    }
                }.toMutableList()
                list.add(svm_node().also { it.index = -1 })
                list.toTypedArray()
            }

            is LongVectorValue -> {
                val list = (quad.`object` as LongVectorValue).vector.mapIndexed { index, d ->
                    svm_node().also { node ->
                        node.index = index + 1
                        node.value = d.toDouble()
                    }
                }.toMutableList()
                list.add(svm_node().also { it.index = -1 })
                list.toTypedArray()
            }

            null -> null
            else -> throw IllegalArgumentException("invalid type of ${quad.`object`}")
        }
    }.toTypedArray()


}
