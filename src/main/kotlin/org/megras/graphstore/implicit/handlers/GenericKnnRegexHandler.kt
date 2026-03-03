package org.megras.graphstore.implicit.handlers

import org.megras.data.graph.URIValue
import org.megras.graphstore.implicit.ImplicitRelationHandler
import org.megras.graphstore.implicit.ImplicitRelationMutableQuadSet
import org.megras.graphstore.implicit.RegexImplicitRelationHandler
import org.megras.util.Constants

/**
 * A regex-based implicit relation handler that matches k-NN predicates with configurable embedding predicates.
 *
 * Matches predicates of the form: `http://megras.org/implicit/<k>nn/<predicateName>`
 *
 * Examples:
 * - `http://megras.org/implicit/5nn/clipEmbedding` - 5-NN using clipEmbedding predicate
 * - `http://megras.org/implicit/10nn/averageColor` - 10-NN using averageColor predicate
 *
 * The predicateName is resolved to a full URI: `http://megras.org/derived/<predicateName>`
 */
class GenericKnnRegexHandler : RegexImplicitRelationHandler {
    // Matches patterns like: .../5nn/clipEmbedding or .../10nn/someEmbedding
    private val regex = Regex(".*/([1-9]\\d*)nn/([a-zA-Z][a-zA-Z0-9]*)$")
    private val handlerCache = mutableMapOf<String, GenericKnnHandler>()
    private var quadSet: ImplicitRelationMutableQuadSet? = null

    fun init(quadSet: ImplicitRelationMutableQuadSet) {
        this.quadSet = quadSet
    }

    override fun matchPredicate(predicate: URIValue): Map<String, String>? {
        val match = regex.matchEntire(predicate.value)
        return if (match != null) {
            mapOf(
                "k" to match.groupValues[1],
                "predicateName" to match.groupValues[2]
            )
        } else {
            null
        }
    }

    override fun getHandler(params: Map<String, String>): ImplicitRelationHandler {
        val k = params["k"]?.toIntOrNull() ?: throw IllegalArgumentException("Invalid k")
        val predicateName = params["predicateName"] ?: throw IllegalArgumentException("Invalid predicate name")

        val cacheKey = "${k}_$predicateName"
        return handlerCache.getOrPut(cacheKey) {
            // Resolve the predicate name to a full derived predicate URI
            val embeddingPredicate = URIValue("${Constants.DERIVED_PREFIX}/$predicateName")
            val handler = GenericKnnHandler(k, embeddingPredicate, predicateName)
            quadSet?.let { handler.init(it) }
            handler
        }
    }
}

