package org.megras.api.rest.handlers

import io.javalin.http.Context
import io.javalin.openapi.*
import org.megras.api.rest.GetRequestHandler
import org.megras.api.rest.RestErrorStatus
import org.megras.api.rest.data.ApiQuad
import org.megras.api.rest.data.ApiQueryResult
import org.megras.data.graph.QuadValue
import org.megras.graphstore.QuadSet

/**
 * Handler for fetching the neighborhood of a node in the graph.
 * Used by the graph visualization to dynamically expand nodes.
 */
class GraphNeighborhoodHandler(private val quads: QuadSet) : GetRequestHandler {

    @OpenApi(
        summary = "Queries the Graph for quads in the neighborhood of a specific URI (as subject or object).",
        path = "/query/neighborhood",
        tags = ["Query"],
        operationId = OpenApiOperation.AUTO_GENERATE,
        methods = [HttpMethod.GET],
        queryParams = [
            OpenApiParam("uri", String::class, description = "The URI to find neighborhood quads for", required = true)
        ],
        responses = [
            OpenApiResponse("200", [OpenApiContent(ApiQueryResult::class)]),
            OpenApiResponse("400", [OpenApiContent(RestErrorStatus::class)]),
        ]
    )
    override fun get(ctx: Context) {
        val uriParam = ctx.queryParam("uri")

        if (uriParam.isNullOrBlank()) {
            throw RestErrorStatus(400, "Missing 'uri' parameter")
        }

        // Wrap in angle brackets if not already, so QuadValue.of() parses it as a URI
        val wrappedUri = if (uriParam.startsWith("<") && uriParam.endsWith(">")) {
            uriParam
        } else {
            "<$uriParam>"
        }
        val uri = QuadValue.of(wrappedUri)

        // Get quads where this URI is either subject or object
        val asSubject = quads.filterSubject(uri)
        val asObject = quads.filterObject(uri)

        val allQuads = (asSubject + asObject).distinctBy {
            Triple(it.subject.toString(), it.predicate.toString(), it.`object`.toString())
        }.map { ApiQuad(it) }

        ctx.json(ApiQueryResult(allQuads))
    }
}
