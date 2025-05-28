package org.megras.graphstore.derived.handlers

import kotlinx.coroutines.runBlocking
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.graph.FloatVectorValue
import org.megras.data.graph.URIValue
import org.megras.data.mime.MimeType
import org.megras.graphstore.QuadSet
import org.megras.graphstore.derived.DerivedRelationHandler
import org.megras.util.Constants
import org.megras.util.FileUtil
import org.megras.util.embeddings.ClipEmbedderClient

class ClipEmbeddingHandler(private val quadSet: QuadSet, private val objectStore: FileSystemObjectStore) : DerivedRelationHandler<FloatVectorValue> {
    override val predicate: URIValue = getPredicate()

    companion object {
        fun getPredicate(): URIValue {
            return URIValue("${Constants.DERIVED_PREFIX}/clipEmbedding")
        }
    }

    override fun canDerive(subject: URIValue): Boolean {
        val osr = FileUtil.getOsr(subject, this.quadSet, this.objectStore) ?: return false

        return when (osr.descriptor.mimeType) { //TODO technically, video should also be supported
            MimeType.BMP,
            MimeType.GIF,
            MimeType.JPEG_I,
            MimeType.PNG,
            MimeType.TIFF -> true

            else -> false
        }
    }

    override fun derive(subject: URIValue): Collection<FloatVectorValue> {
        val path = FileUtil.getPath(subject, this.quadSet, this.objectStore) ?: return emptyList()

        val embedding: List<Float> = runBlocking {
            val client = ClipEmbedderClient("localhost", 50051)
            try {
                val embedding: List<Float> = client.getTextEmbedding(path)
                return@runBlocking embedding

            } catch (e: Exception) {
                println("Error getting text embedding for '$path': ${e.message}")
                return@runBlocking emptyList<Float>()
            } finally {
                client.close()
            }
        }

        return listOf(
            FloatVectorValue(embedding)
        )
    }
}