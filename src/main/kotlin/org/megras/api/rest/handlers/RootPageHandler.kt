package org.megras.api.rest.handlers

import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataSet
import io.javalin.http.Context
import java.io.File

class RootPageHandler {

    fun get(ctx: Context) {
        try {
            val projectDir = System.getProperty("user.dir")
            val readmeFile = File(projectDir, "GETTING_STARTED.md")

            if (readmeFile.exists() && readmeFile.isFile) {
                val markdownContent = readmeFile.readText(Charsets.UTF_8)

                // Initialize Flexmark parser and renderer
                val options = MutableDataSet()
                val parser = Parser.builder(options).build()
                val renderer = HtmlRenderer.builder(options).build()

                val document = parser.parse(markdownContent)
                val bodyContent = renderer.render(document)
                val htmlContent = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>Getting Started</title>
                    <link rel="stylesheet" type="text/css" href="/static/styles.css">
                </head>
                <body>
                    $bodyContent
                </body>
                </html>
                """.trimIndent()

                ctx.contentType("text/html; charset=utf-8")
                ctx.result(htmlContent)
            } else {
                ctx.status(404).result("GETTING_STARTED.md not found in project root.")
            }
        } catch (e: Exception) {
            // Consider logging the exception server-side
            // e.printStackTrace()
            ctx.status(500).result("Error reading or parsing GETTING_STARTED.md: ${e.message}")
        }
    }
}