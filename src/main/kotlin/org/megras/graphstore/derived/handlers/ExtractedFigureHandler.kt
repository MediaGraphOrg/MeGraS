package org.megras.graphstore.derived.handlers

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.runBlocking
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.graph.LocalQuadValue
import org.megras.data.graph.URIValue
import org.megras.data.model.MediaType
import org.megras.graphstore.MutableQuadSet
import org.megras.graphstore.QuadSet
import org.megras.graphstore.derived.DerivedRelationHandler
import org.megras.util.Constants
import org.megras.util.FileUtil
import org.megras.util.PdfCropUtil
import org.megras.util.ServiceConfig
import org.megras.util.services.DoclingClient

class ExtractedFigureHandler(private val quadSet: QuadSet, private val objectStore: FileSystemObjectStore) : DerivedRelationHandler<LocalQuadValue> {
    override val predicate: URIValue = getPredicate()

    companion object {
        fun getPredicate(): URIValue {
            return URIValue("${Constants.DERIVED_PREFIX}/extractedFigure")
        }
    }

    override fun canDerive(subject: URIValue): Boolean {
        val osr = FileUtil.getOsr(subject, this.quadSet, this.objectStore) ?: return false

        return when (MediaType.mimeTypeMap[osr.descriptor.mimeType]) {
            MediaType.DOCUMENT -> true
            else -> false
        }
    }

    override fun derive(subject: URIValue): Collection<LocalQuadValue> {
        val path = FileUtil.getPath(subject, this.quadSet, this.objectStore) ?: return emptyList()

        // Use DoclingClient to fetch raw JSON; parse to extract figures
        val figures: List<Map<String, Any?>> = runBlocking {
            val client = DoclingClient(ServiceConfig.grpcHost, ServiceConfig.grpcPort)
            try {
                val json = client.extractDocJson(path)
                val mapper = ObjectMapper().registerKotlinModule()
                @Suppress("UNCHECKED_CAST")
                val root = mapper.readValue(json, Map::class.java) as Map<String, Any?>
                val pictures = (root["pictures"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
                pictures
            } catch (e: Exception) {
                println("Error extracting figures for '$path': ${e.message}")
                emptyList()
            } finally {
                client.close()
            }
        }

        if (figures.isEmpty()) {
            return emptyList()
        }

        return try {
            PdfCropUtil.storeCrops(
                pdfPath = path,
                items = figures,
                namePrefix = "figure",
                quadSet = quadSet as MutableQuadSet,
                objectStore = objectStore
            )
        } catch (e: Exception) {
            println("Error storing figure crops for '$path': ${e.message}")
            emptyList()
        }
    }
}