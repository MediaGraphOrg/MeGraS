package org.megras.api.rest.handlers

import io.javalin.http.Context
import io.javalin.openapi.HttpMethod
import io.javalin.openapi.OpenApi
import io.javalin.openapi.OpenApiResponse
import org.megras.api.rest.GetRequestHandler

class SparqlUiHandler : GetRequestHandler {

    @OpenApi(
        path = "/sparqlui",
        methods = [HttpMethod.GET],
        summary = "Redirects to the SPARQL query interface.",
        description = "Provides a user interface for executing SPARQL queries against the repository.",
        tags = ["Public"],
        responses = [
            OpenApiResponse(status = "302", description = "Redirects to the UI.")
        ]
    )
    override fun get(ctx: Context) {
        //TODO: redirect to our hosted version of snorql
        ctx.redirect("https://ammar257ammar.github.io/Snorql-UI")
    }
}