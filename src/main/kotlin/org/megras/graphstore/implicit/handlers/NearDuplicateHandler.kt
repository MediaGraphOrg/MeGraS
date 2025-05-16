package org.megras.graphstore.implicit.handlers

import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.fs.StoredObjectId
import org.megras.data.graph.LocalQuadValue
import org.megras.data.graph.StringValue
import org.megras.data.graph.URIValue
import org.megras.data.schema.MeGraS
import org.megras.graphstore.QuadSet
import org.megras.graphstore.implicit.ImplicitRelationHandler
import org.megras.graphstore.implicit.ImplicitRelationMutableQuadSet
import org.megras.util.Constants
import org.megras.util.embeddings.ClipEmbeddings
import org.megras.graphstore.Distance

class NearDuplicateHandler(private val objectStore: FileSystemObjectStore) : ImplicitRelationHandler{
    companion object {
        private const val DISTANCE = "COSINE"
        private const val DISTANCE_THRESHOLD = 0.1
    }

    override val predicate: URIValue = URIValue("${Constants.IMPLICIT_PREFIX}/nearDuplicate")

    private lateinit var quadSet: ImplicitRelationMutableQuadSet

    override fun init(quadSet: ImplicitRelationMutableQuadSet) {
        this.quadSet = quadSet
    }

    private fun getEmbeddingCandidatesAndCache(subject: LocalQuadValue): EmbeddingCandidatesResult? {
        val path = getPath(subject) ?: return null

        val embedding = ClipEmbeddings.getImageEmbedding(path)

        val candidates = quadSet.filter { it.subject is LocalQuadValue && it.subject != subject }
            .map { it.subject as LocalQuadValue }
            .toSet()
        val embeddingCache = candidates.associateWith { ClipEmbeddings.getImageEmbedding(getPath(it) ?: return@associateWith null) }
        return EmbeddingCandidatesResult(embedding, candidates, embeddingCache)
    }

    private data class EmbeddingCandidatesResult(
        val embedding: FloatArray,
        val candidates: Set<URIValue>,
        val embeddingCache: Map<LocalQuadValue, FloatArray?>
    )

    private fun getPath(subject: URIValue): String? {
        val canonicalId = this.quadSet.filter(
            setOf(subject),
            setOf(MeGraS.CANONICAL_ID.uri),
            null
        ).firstOrNull()?.`object` as? StringValue ?: return null

        val osId = StoredObjectId.of(canonicalId.value) ?: return null
        return objectStore.storageFile(osId).absolutePath
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
        TODO("Not yet implemented")
    }
}