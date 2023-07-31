package org.megras.api.rest.handlers

import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import io.javalin.openapi.*
import org.megras.api.rest.PostRequestHandler
import org.megras.api.rest.RestErrorStatus
import org.megras.api.rest.data.ApiAddQuad
import org.megras.api.rest.data.ApiQueryResult
import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.graphstore.MutableQuadSet

class AddQuadRequestHandler(private val quads: MutableQuadSet) : PostRequestHandler {

    @OpenApi(
        summary = "Adds new quads to the graph.",
        path = "/add/quads",
        tags = ["Addition"],
        operationId = OpenApiOperation.AUTO_GENERATE,
        methods = [HttpMethod.POST],
        requestBody = OpenApiRequestBody([OpenApiContent(ApiAddQuad::class)]),
        responses = [
            OpenApiResponse("200", [OpenApiContent(ApiQueryResult::class)]),
            OpenApiResponse("400", [OpenApiContent(RestErrorStatus::class)]),
        ]
    )
    override fun post(ctx: Context) {

        val body = try {
            ctx.bodyAsClass(ApiAddQuad::class.java)
        } catch (e: BadRequestResponse) {
            throw RestErrorStatus(400, "invalid body")
        }

        body.quads.forEach { quad ->
            quads.add(Quad(QuadValue.of(quad.s), QuadValue.of(quad.p), QuadValue.of(quad.o)))
        }

        ctx.status(200)
    }
}