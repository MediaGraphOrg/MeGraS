package org.megras.graphstore.implicit.handlers

import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.graph.FloatVectorValue
import org.megras.data.graph.LocalQuadValue
import org.megras.data.graph.Quad
import org.megras.data.graph.URIValue
import org.megras.graphstore.BasicQuadSet
import org.megras.graphstore.QuadSet
import org.megras.graphstore.implicit.ImplicitRelationHandler
import org.megras.graphstore.implicit.ImplicitRelationMutableQuadSet
import org.megras.util.Constants
import org.megras.graphstore.Distance
import org.megras.graphstore.derived.handlers.ClipEmbeddingHandler

class ClipNearDuplicateHandler() : ImplicitRelationHandler{
    companion object {
        private const val DISTANCE = "COSINE"
        private const val DISTANCE_THRESHOLD = 0.1
        private val CLIP_EMBEDDING_PREDICATE = ClipEmbeddingHandler.getPredicate()
    }

    override val predicate: URIValue = URIValue("${Constants.IMPLICIT_PREFIX}/clipNearDuplicate")

    private lateinit var quadSet: ImplicitRelationMutableQuadSet

    override fun init(quadSet: ImplicitRelationMutableQuadSet) {
        this.quadSet = quadSet
    }

    private fun getEmbeddingCandidatesAndCache(subject: LocalQuadValue): EmbeddingCandidatesResult? {
        val embedding = getImageEmbedding(subject) ?: return null

        val candidates = quadSet.filter { it.subject is LocalQuadValue && it.subject != subject }
            .map { it.subject as LocalQuadValue }
            .toSet()
        val embeddingCache = candidates.associateWith { getImageEmbedding(it) }
        return EmbeddingCandidatesResult(embedding, candidates, embeddingCache)
    }

    private data class EmbeddingCandidatesResult(
        val embedding: FloatArray?,
        val candidates: Set<URIValue>,
        val embeddingCache: Map<LocalQuadValue, FloatArray?>
    )

    private fun getImageEmbedding(subject: LocalQuadValue) : FloatArray? {
        val embedding = this.quadSet.filter(
            setOf(subject),
            setOf(CLIP_EMBEDDING_PREDICATE),
            null
        ).firstOrNull()?.`object` as? FloatVectorValue ?: return null
        return embedding.vector
    }

    // same function because relationship is symmetric
    private fun findValues(value: URIValue): Set<URIValue> {
        if (value !is LocalQuadValue) {
            return emptySet()
        }
        val (embedding, candidates, embeddingCache) = getEmbeddingCandidatesAndCache(value) ?: return emptySet()
        val dist = Distance.valueOf(DISTANCE).distance()

        return candidates.filter {
            val canEmbedding = embeddingCache[it] ?: return@filter false
            val distance = dist.distance(embedding!!, canEmbedding)
            distance < DISTANCE_THRESHOLD // Adjust the threshold as needed
        }.toSet()
    }

    override fun findObjects(subject: URIValue): Set<URIValue> {
        return findValues(subject)
    }

    override fun findSubjects(`object`: URIValue): Set<URIValue> {
        return findValues(`object`)
    }

    override fun findAll(): QuadSet {
        val subjects = this.quadSet.filter { it.subject is LocalQuadValue }
            .map { it.subject as LocalQuadValue }
            .toSet()
        val embeddingCache = subjects.associateWith { getImageEmbedding(it) }
        val dist = Distance.valueOf(DISTANCE).distance()

        val pairs = mutableSetOf<Quad>()
        for (subject1 in subjects) {
            val embedding1 = embeddingCache[subject1] ?: continue
            for (subject2 in subjects) {
                if (subject1 != subject2) {
                    val embedding2 = embeddingCache[subject2] ?: continue
                    val distance = dist.distance(embedding1, embedding2)
                    if (distance < DISTANCE_THRESHOLD) {
                        pairs.add(Quad(subject1, predicate, subject2))
                    }
                }
            }
        }
        return BasicQuadSet(pairs)
    }
}
