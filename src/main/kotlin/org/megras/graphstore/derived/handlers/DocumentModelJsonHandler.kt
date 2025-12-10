package org.megras.graphstore.derived.handlers

import kotlinx.coroutines.runBlocking
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.graph.QuadValue
import org.megras.data.graph.StringValue
import org.megras.data.graph.URIValue
import org.megras.data.model.MediaType
import org.megras.graphstore.QuadSet
import org.megras.graphstore.derived.DerivedRelationHandler
import org.megras.util.Constants
import org.megras.util.FileUtil
import org.megras.util.ServiceConfig
import org.megras.util.services.DoclingClient

class DocumentModelJsonHandler(private val quadSet: QuadSet, private val objectStore: FileSystemObjectStore) : DerivedRelationHandler<QuadValue> {
    override val predicate: URIValue = getPredicate()
    override val requiresExternalService: Boolean = true

    companion object {
        fun getPredicate(): URIValue {
            return URIValue("${Constants.DERIVED_PREFIX}/documentModelJson")
        }
    }

    override fun canDerive(subject: URIValue): Boolean {
        val osr = FileUtil.getOsr(subject, this.quadSet, this.objectStore) ?: return false
        return when (MediaType.mimeTypeMap[osr.descriptor.mimeType]) {
            MediaType.DOCUMENT -> true
            else -> false
        }
    }

    override fun derive(subject: URIValue): Collection<QuadValue> {
        val path = FileUtil.getPath(subject, this.quadSet, this.objectStore) ?: return emptyList()

        val rawJson: String = runBlocking {
            val client = DoclingClient(ServiceConfig.grpcHost, ServiceConfig.grpcPort)
            try {
                client.extractDocJson(path)
            } catch (e: Exception) {
                println("Error extracting Docling JSON for '$path': ${e.message}")
                ""
            } finally {
                client.close()
            }
        }

        if (rawJson.isBlank()) {
            return emptyList()
        }

        // Return raw JSON without post-processing; caption resolution will be handled by paragraph extraction/linking.
        return listOf(StringValue(rawJson))
    }
}
