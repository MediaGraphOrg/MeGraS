package org.megras.graphstore.derived.handlers

import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.fs.StoredObjectId
import org.megras.data.graph.FloatVectorValue
import org.megras.data.graph.LocalQuadValue
import org.megras.data.graph.StringValue
import org.megras.data.graph.URIValue
import org.megras.data.schema.MeGraS
import org.megras.graphstore.QuadSet
import org.megras.graphstore.derived.DerivedRelationHandler
import org.megras.util.Constants
import org.megras.util.embeddings.ClipEmbeddings

class ClipEmbeddingHandler(private val quadSet: QuadSet, private val objectStore: FileSystemObjectStore) : DerivedRelationHandler<FloatVectorValue> {
    override val predicate: URIValue = getPredicate()

    companion object {
        fun getPredicate(): URIValue {
            return URIValue("${Constants.DERIVED_PREFIX}/clipEmbedding")
        }
    }

    override fun canDerive(subject: URIValue): Boolean {
        if (subject !is LocalQuadValue) {
            return false
        }

        getPath(subject) ?: return false

        return true
    }

    private fun getPath(subject: URIValue): String? {
        val canonicalId = this.quadSet.filter(
            setOf(subject),
            setOf(MeGraS.CANONICAL_ID.uri),
            null
        ).firstOrNull()?.`object` as? StringValue ?: return null

        val osId = StoredObjectId.of(canonicalId.value) ?: return null
        return objectStore.storageFile(osId).absolutePath
    }

    override fun derive(subject: URIValue): Collection<FloatVectorValue> {
        if (subject !is LocalQuadValue) {
            return emptyList()
        }

        val path = getPath(subject) ?: return emptyList()

        val embedding = ClipEmbeddings.getImageEmbedding(path)

        return listOf(
            FloatVectorValue(embedding)
        )
    }
}