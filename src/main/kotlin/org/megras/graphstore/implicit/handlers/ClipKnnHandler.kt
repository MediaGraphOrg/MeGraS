package org.megras.graphstore.implicit.handlers

import org.megras.data.graph.FloatVectorValue
import org.megras.data.graph.LocalQuadValue
import org.megras.data.graph.URIValue
import org.megras.graphstore.Distance
import org.megras.graphstore.QuadSet
import org.megras.graphstore.derived.handlers.ClipEmbeddingHandler
import org.megras.graphstore.implicit.ImplicitRelationHandler
import org.megras.graphstore.implicit.ImplicitRelationMutableQuadSet
import org.megras.util.Constants

class ClipKnnHandler(private val k: Int) : ImplicitRelationHandler {
    companion object {
        private const val DISTANCE = "COSINE"
        private val CLIP_EMBEDDING_PREDICATE = ClipEmbeddingHandler.getPredicate()
    }

    override val predicate: URIValue = URIValue("${Constants.IMPLICIT_PREFIX}/clip${k}nn")

    private lateinit var quadSet: ImplicitRelationMutableQuadSet

    override fun init(quadSet: ImplicitRelationMutableQuadSet) {
        this.quadSet = quadSet
    }

    private fun getImageEmbedding(subject: LocalQuadValue) : FloatVectorValue? {
        val embedding = this.quadSet.filter(
            setOf(subject),
            setOf(CLIP_EMBEDDING_PREDICATE),
            null
        ).firstOrNull()?.`object` as? FloatVectorValue ?: return null
        return embedding
    }

    fun findValues(value: URIValue): Set<URIValue> {
        if (value !is LocalQuadValue) {
            return emptySet()
        }
        val embedding = getImageEmbedding(value) ?: return emptySet()
        return this.quadSet.nearestNeighbor(
                CLIP_EMBEDDING_PREDICATE,
                embedding,
                k,
                Distance.valueOf(DISTANCE),
                false
            )
            .map { it.subject }
            .filterIsInstance<LocalQuadValue>()
            .toSet()
    }

    override fun findObjects(subject: URIValue): Set<URIValue> {
        TODO("Not yet implemented")
    }

    override fun findSubjects(`object`: URIValue): Set<URIValue> {
        TODO("Not yet implemented")
    }

    override fun findAll(): QuadSet {
        TODO("Not yet implemented")
    }
}