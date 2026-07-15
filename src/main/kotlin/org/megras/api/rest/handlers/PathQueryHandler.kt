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

    private companion object {
        // Traversing the graph is exponential in depth; without bounds an
        // unauthenticated caller can pin CPU/memory by asking for many seeds
        // with Int.MAX_VALUE depth.
        const val MAX_PATH_DEPTH = 5
        const val MAX_SEEDS = 256
        const val MAX_RESULT_QUADS = 5_000
    }

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
        if (seeds.size > MAX_SEEDS) {
            throw RestErrorStatus(400, "too many seeds (max $MAX_SEEDS)")
        }
        val predicates = query.predicates?.mapNotNull { if (it != null) QuadValue.of(it) else null } ?: throw RestErrorStatus(400, "invalid query")
        // Clamp caller-supplied depth; a missing/zero depth used to mean
        // Int.MAX_VALUE which enables unbounded graph traversal.
        val maxDepth = if (query.maxDepth > 0) minOf(query.maxDepth, MAX_PATH_DEPTH) else MAX_PATH_DEPTH
        val reverse = query.reverse

        val results = mutableSetOf<Quad>()

        var iteration = 0
        var start = seeds.toSet()

        while (iteration++ < maxDepth) {

            val step = if (reverse) {
                quads.filter(null, predicates, start)
            } else {
                quads.filter(start, predicates, null)
            }

            if (step.isEmpty()) {
                break
            }

            results.addAll(step)
            // Stop once we have collected enough to satisfy any reasonable
            // exploratory query but not enough to exhaust memory.
            if (results.size >= MAX_RESULT_QUADS) {
                break
            }
            start = if (reverse) {
                step.map { it.subject }.toSet()
            } else {
                step.map { it.`object` }.toSet()
            }


        }

        ctx.json(ApiQueryResult(results.take(MAX_RESULT_QUADS).map { ApiQuad(it) }))
    }
}