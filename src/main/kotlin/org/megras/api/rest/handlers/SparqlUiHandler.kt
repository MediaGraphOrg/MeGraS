package org.megras.api.rest.handlers

import io.javalin.http.Context
import org.megras.api.rest.GetRequestHandler

class SparqlUiHandler : GetRequestHandler {
    override fun get(ctx: Context) {
        ctx.redirect("/Snorql-UI/index.html")
    }
}