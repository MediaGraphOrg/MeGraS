package org.megras.api.rest.handlers

import io.javalin.http.Context
import org.megras.api.rest.GetRequestHandler

class SparqlUiHandler : GetRequestHandler {
    override fun get(ctx: Context) {
        //TODO: redirect to our hosted version of snorql
        ctx.redirect("https://ammar257ammar.github.io/Snorql-UI")
    }
}