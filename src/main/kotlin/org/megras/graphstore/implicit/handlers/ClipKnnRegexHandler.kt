package org.megras.graphstore.implicit.handlers

import org.megras.data.graph.URIValue
import org.megras.graphstore.implicit.ImplicitRelationHandler
import org.megras.graphstore.implicit.ImplicitRelationMutableQuadSet
import org.megras.graphstore.implicit.RegexImplicitRelationHandler

class ClipKnnRegexHandler : RegexImplicitRelationHandler {
    private val regex = Regex(".*/clip(\\d+)nn$")
    private val handlerCache = mutableMapOf<Int, ClipKnnHandler>()
    private var quadSet: ImplicitRelationMutableQuadSet? = null

    fun init(quadSet: ImplicitRelationMutableQuadSet) {
        this.quadSet = quadSet
    }

    override fun matchPredicate(predicate: URIValue): Map<String, String>? {
        val match = regex.matchEntire(predicate.value)
        return if (match != null) {
            mapOf("k" to match.groupValues[1])
        } else {
            null
        }
    }

    override fun getHandler(params: Map<String, String>): ImplicitRelationHandler {
        val k = params["k"]?.toIntOrNull() ?: throw IllegalArgumentException("Invalid k")
        return handlerCache.getOrPut(k) {
            val handler = ClipKnnHandler(k)
            quadSet?.let { handler.init(it) }
            handler
        }
    }
}

