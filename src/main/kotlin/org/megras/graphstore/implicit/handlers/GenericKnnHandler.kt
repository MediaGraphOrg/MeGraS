package org.megras.graphstore.implicit.handlers

import org.megras.data.graph.FloatVectorValue
import org.megras.data.graph.LocalQuadValue
import org.megras.data.graph.Quad
import org.megras.data.graph.URIValue
import org.megras.graphstore.BasicQuadSet
import org.megras.graphstore.Distance
import org.megras.graphstore.QuadSet
import org.megras.graphstore.implicit.ImplicitRelationHandler
import org.megras.graphstore.implicit.ImplicitRelationMutableQuadSet
import org.megras.util.Constants
import java.util.concurrent.ConcurrentHashMap

/**
 * A generic k-NN implicit relation handler that works with any embedding predicate.
 *
 * @param k The number of nearest neighbors to find
 * @param embeddingPredicate The predicate used to retrieve embeddings for subjects
 * @param predicateName A short name for the embedding predicate (used in the implicit predicate URI)
 */
class GenericKnnHandler(
    private val k: Int,
    private val embeddingPredicate: URIValue,
    private val predicateName: String
) : ImplicitRelationHandler {

    companion object {
        private const val DISTANCE = "COSINE"
    }

    override val predicate: URIValue = URIValue("${Constants.IMPLICIT_PREFIX}/${k}nn/$predicateName")

    private lateinit var quadSet: ImplicitRelationMutableQuadSet

    override fun init(quadSet: ImplicitRelationMutableQuadSet) {
        this.quadSet = quadSet
    }

    private fun getEmbedding(subject: LocalQuadValue): FloatVectorValue? {
        return this.quadSet.filter(
            setOf(subject),
            setOf(embeddingPredicate),
            null
        ).firstOrNull()?.`object` as? FloatVectorValue
    }

    override fun findObjects(subject: URIValue): Set<URIValue> {
        if (subject !is LocalQuadValue) {
            return emptySet()
        }
        val embedding = getEmbedding(subject) ?: return emptySet()
        return this.quadSet.nearestNeighbor(
            embeddingPredicate,
            embedding,
            k + 1, // +1 because we include the subject itself
            Distance.valueOf(DISTANCE),
            false
        )
            .map { it.subject }
            .filterIsInstance<LocalQuadValue>()
            .filter { it != subject } // Exclude the subject itself
            .toSet()
    }

    override fun findSubjects(`object`: URIValue): Set<URIValue> {
        // This is a "reverse k-NN" query, which is computationally expensive without a pre-computed graph or specialized index.
        // Returning an empty set to indicate this query is not efficiently supported.
        return emptySet()
    }

    override fun findAll(): QuadSet {
        // 1. Bulk fetch all embeddings in one query
        val allEmbeddingQuads = this.quadSet.filter(null, setOf(embeddingPredicate), null)

        // 2. Create an in-memory cache of subjects and their embeddings
        val embeddingCache = allEmbeddingQuads.mapNotNull { quad ->
            val subject = quad.subject as? LocalQuadValue
            val embedding = quad.`object` as? FloatVectorValue
            if (subject != null && embedding != null) {
                subject to embedding
            } else {
                null
            }
        }.toMap()

        val resultingQuads = ConcurrentHashMap.newKeySet<Quad>()

        // 3. For each subject, find its k-NN in parallel
        embeddingCache.entries.parallelStream().forEach { (subject, embedding) ->
            val neighbors = this.quadSet.nearestNeighbor(
                embeddingPredicate,
                embedding,
                k + 1, // +1 because we include the subject itself
                Distance.valueOf(DISTANCE),
                false
            )
                .mapNotNull { it.subject as? URIValue }
                .filter { it != subject } // Exclude the subject itself

            for (neighbor in neighbors) {
                resultingQuads.add(Quad(subject, this.predicate, neighbor))
            }
        }

        return BasicQuadSet(resultingQuads)
    }
}

