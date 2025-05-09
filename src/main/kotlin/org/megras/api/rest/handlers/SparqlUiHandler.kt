package org.megras.api.rest.handlers

import io.javalin.http.ContentType
import io.javalin.http.Context
import org.megras.api.rest.GetRequestHandler


class SparqlUiHandler : GetRequestHandler {
    override fun get(ctx: Context) {
        // Read the HTML file from resources
        val htmlFile = this::class.java.getResource("/public/Snorql-UI/index.html")
            ?: throw IllegalStateException("HTML file not found in resources")
        // Read the content of the HTML file
        val htmlContent = htmlFile.readText()
        // Set the content type to HTML
        ctx.contentType(ContentType.TEXT_HTML.mimeType)
        // Write the HTML content to the response
        ctx.result(htmlContent)
    }
}