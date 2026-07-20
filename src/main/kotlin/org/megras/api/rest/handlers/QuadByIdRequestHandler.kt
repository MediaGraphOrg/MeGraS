package org.megras.api.rest.handlers

import io.javalin.http.Context
import io.javalin.openapi.*
import org.apache.jena.graph.Triple
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.riot.Lang
import org.apache.jena.riot.RDFDataMgr
import org.megras.api.rest.GetRequestHandler
import org.megras.api.rest.RestErrorStatus
import org.megras.api.rest.data.ApiQuad
import org.megras.graphstore.QuadSet
import org.megras.id.SemanticId
import org.megras.lang.sparql.SparqlUtil

/**
 * Fetches a single quad by its storage-independent semantic id (content hash
 * of (s,p,o)); see [org.megras.id.id]. Response format is driven solely by the
 * `Accept` header:
 * - `application/json`     -> [ApiQuad] projection (Jackson via ctx.json)
 * - `text/turtle`          -> single-triple Turtle document
 * - `application/rdf+xml`  -> single-triple RDF/XML document
 * Unacceptable Accept → 406. Malformed id → 400. Unknown id → 404.
 *
 * Refuses to expose internal storage pointers; the path param IS the semantic
 * id, which is safe to persist and compare externally. RDF rendering goes
 * through [SparqlUtil.toTriple], which covers local terms ([LocalQuadValue])
 * on their full IRI.
 */
class QuadByIdRequestHandler(private val quads: QuadSet) : GetRequestHandler {

    @OpenApi(
        summary = "Retrieves a single quad by its semantic id.",
        path = "/quad/{id}",
        tags = ["Query"],
        operationId = OpenApiOperation.AUTO_GENERATE,
        methods = [HttpMethod.GET],
        pathParams = [OpenApiParam(name = "id", type = String::class, description = "Semantic id (base64 multihash) of the quad.")],
        responses = [
            OpenApiResponse("200", [OpenApiContent(ApiQuad::class)]),
            OpenApiResponse("400"),
            OpenApiResponse("404"),
            OpenApiResponse("406")
        ]
    )
    override fun get(ctx: Context) {
        val id = SemanticId.fromString(ctx.pathParam("id"))
            ?: throw RestErrorStatus(400, "malformed semantic id")
        val quad = quads.getId(id) ?: throw RestErrorStatus.notFound

        when (negotiate(ctx.headerMap()["Accept"])) {
            "application/json" -> ctx.json(ApiQuad(quad))
            "text/turtle" -> writeRdf(ctx, quad, Lang.TURTLE)
            "application/rdf+xml" -> writeRdf(ctx, quad, Lang.RDFXML)
            else -> throw RestErrorStatus(406, "Not acceptable")
        }
    }

    private fun writeRdf(ctx: Context, tripleSource: org.megras.data.graph.Quad, lang: Lang) {
        val t: Triple = SparqlUtil.toTriple(tripleSource)
        val model = ModelFactory.createDefaultModel()
        model.graph.add(t)
        ctx.contentType(lang.headerString)
        RDFDataMgr.write(ctx.outputStream(), model, lang)
    }

    /**
     * Minimal RFC 7231 Accept negotiation restricted to the three formats this
     * endpoint serves. Honours q-values and wildcard ranges; selects
     * the supported media type with the highest client-advertised q, server
     * preference order breaking ties. Returns null when nothing acceptable.
     */
    private fun negotiate(accept: String?): String? {
        val supported = listOf("application/json", "text/turtle", "application/rdf+xml")
        if (accept.isNullOrBlank()) return supported.first()
        val entries = mutableListOf<Pair<String, Double>>()
        for (part in accept.split(',')) {
            val tok = part.trim().split(';').map { it.trim() }
            if (tok.isEmpty() || tok[0].isEmpty()) continue
            val range = tok[0]
            var q = 1.0
            for (param in tok.drop(1)) {
                if (param.startsWith("q=")) {
                    val parsed = param.substring(2).toDoubleOrNull()
                    if (parsed != null) q = parsed
                }
            }
            entries.add(range to q)
        }
        var best: String? = null
        var bestQ = 0.0
        for (mime in supported) {
            var maxQ = 0.0
            for ((range, q) in entries) {
                if (matches(range, mime) && q > maxQ) maxQ = q
            }
            if (maxQ > bestQ) {
                bestQ = maxQ
                best = mime
            }
        }
        return if (bestQ > 0.0) best else null
    }

    private fun matches(range: String, mime: String): Boolean {
        val star = "*"
        val any = "$star/$star"
        if (range == mime || range == any) return true
        if (!range.endsWith("/$star")) return false
        val prefix = range.substring(0, range.length - 1)
        return mime.startsWith(prefix)
    }
}
