package org.megras.graphstore.implicit.handlers

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
import java.util.concurrent.ConcurrentHashMap

class ClipNearDuplicateHandler : ImplicitRelationHandler {
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
        // 1. Get embedding for the initial subject
        val subjectEmbedding = getImageEmbedding(subject) ?: return null

        // 2. Find all other subjects that have an embedding
        val candidates = quadSet.filter(null, setOf(CLIP_EMBEDDING_PREDICATE), null)
            .mapNotNull { it.subject as? LocalQuadValue }
            .filter { it != subject }
            .toSet()

        // 3. Bulk fetch embeddings for all candidates
        val embeddingCache = quadSet.filter(candidates, setOf(CLIP_EMBEDDING_PREDICATE), null)
            .mapNotNull { quad ->
                (quad.subject as? LocalQuadValue)?.let { s ->
                    (quad.`object` as? FloatVectorValue)?.let { e ->
                        s to e.vector
                    }
                }
            }.toMap()

        return EmbeddingCandidatesResult(subjectEmbedding, candidates, embeddingCache)
    }

    private data class EmbeddingCandidatesResult(
        val embedding: FloatArray,
        val candidates: Set<URIValue>,
        val embeddingCache: Map<LocalQuadValue, FloatArray>
    )

    private fun getImageEmbedding(subject: LocalQuadValue): FloatArray? {
        return (this.quadSet.filter(
            setOf(subject),
            setOf(CLIP_EMBEDDING_PREDICATE),
            null
        ).firstOrNull()?.`object` as? FloatVectorValue)?.vector
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
            val distance = dist.distance(embedding, canEmbedding)
            distance < DISTANCE_THRESHOLD
        }.toSet()
    }

    override fun findObjects(subject: URIValue): Set<URIValue> {
        return findValues(subject)
    }

    override fun findSubjects(`object`: URIValue): Set<URIValue> {
        return findValues(`object`)
    }

    override fun findAll(): QuadSet {
        // 1. Bulk fetch all embeddings in one query
        val allEmbeddingQuads = this.quadSet.filter(null, setOf(CLIP_EMBEDDING_PREDICATE), null)

        // 2. Create an in-memory cache of subjects and their embeddings
        val embeddingCache = allEmbeddingQuads.mapNotNull { quad ->
            val subject = quad.subject as? LocalQuadValue
            val embedding = (quad.`object` as? FloatVectorValue)?.vector
            if (subject != null && embedding != null) {
                subject to embedding
            } else {
                null
            }
        }.toMap()

        val resultingQuads = ConcurrentHashMap.newKeySet<Quad>()
        val dist = Distance.valueOf(DISTANCE).distance()
        val subjectList = embeddingCache.keys.toList()

        // 3. Process pairs in parallel with an optimized loop to avoid redundant checks
        subjectList.indices.toList().parallelStream().forEach { i ->
            val subject1 = subjectList[i]
            val embedding1 = embeddingCache[subject1]!!

            for (j in (i + 1) until subjectList.size) {
                val subject2 = subjectList[j]
                val embedding2 = embeddingCache[subject2]!!

                val distance = dist.distance(embedding1, embedding2)
                if (distance < DISTANCE_THRESHOLD) {
                    // Add symmetric relationship
                    resultingQuads.add(Quad(subject1, predicate, subject2))
                    resultingQuads.add(Quad(subject2, predicate, subject1))
                }
            }
        }

        return BasicQuadSet(resultingQuads)
    }
}