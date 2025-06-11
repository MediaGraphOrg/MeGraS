package org.megras.api.rest.handlers

import io.javalin.http.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.megras.api.rest.GetRequestHandler

class PredicateInformationHandler : GetRequestHandler {

    override fun get(ctx: Context) {
        // predicateUri from the path is used to identify which predicate to scroll to.
        // If "predicateUri" is not in the path params, default to empty string.
        val predicateUriToJumpTo = ctx.pathParamMap()["predicateUri"] ?: ""

        val htmlResponse = buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html>")
            appendLine("<head>")
            appendLine("    <title>Predicate Information</title>")
            appendLine("    <meta charset=\"UTF-8\">")
            appendLine("</head>")
            appendLine("<body>")
            appendLine("    <h1>Available Predicates</h1>")

            if (predicates.isEmpty()) {
                appendLine("    <p>No predicates loaded or found.</p>")
            } else {
                predicates.forEach { predicateInfo ->
                    appendLine("    <section id=\"${predicateInfo.uri}\">")
                    appendLine("        <h2>${predicateInfo.title} (${predicateInfo.uri})</h2>")
                    appendLine("        <p>${predicateInfo.description}</p>")
                    appendLine("    </section>")
                    appendLine("    <br><br><br><br>")
                    appendLine("    <hr>")
                }
            }

            appendLine("    <script>")
            appendLine("      document.addEventListener('DOMContentLoaded', function() {")
            // Only attempt to scroll if predicateUriToJumpTo is not blank.
            if (predicateUriToJumpTo.isNotBlank()) {
                // Use manual escaping for JS string literal as per current code.
                appendLine("        const targetIdString = \"$predicateUriToJumpTo\";")
                appendLine("        const element = document.getElementById(targetIdString);")
                appendLine("        if (element) {")
                appendLine("          element.scrollIntoView();")
                appendLine("        } else {")
                appendLine("          console.warn('Predicate section with ID \"' + targetIdString + '\" not found for scrolling.');")
                appendLine("        }")
            } else {
                appendLine("        // No specific predicate URI provided or it is blank, starting at the top of the page.")
            }
            appendLine("      });")
            appendLine("    </script>")

            appendLine("</body>")
            appendLine("</html>")
        }

        ctx.html(htmlResponse)
    }

    private val predicates: List<PredicateInfo> by lazy {
        try {
            val resourcePath = "/predicates.json"
            val inputStream = PredicateInformationHandler::class.java.getResourceAsStream(resourcePath)
            val jsonText = inputStream?.bufferedReader()?.use { it.readText() }
            if (jsonText != null) {
                Json.decodeFromString<List<PredicateInfo>>(jsonText)
            } else {
                System.err.println("predicates.json not found in resources.")
                emptyList()
            }
        } catch (e: Exception) {
            System.err.println("Error parsing predicates.json: ${e.message}")
            emptyList()
        }
    }
}

@Serializable
data class PredicateInfo(val uri: String, val description: String, val title: String)