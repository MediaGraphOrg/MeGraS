package org.megras.graphstore.derived.handlers

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.graph.LocalQuadValue
import org.megras.data.graph.StringValue
import org.megras.data.graph.URIValue
import org.megras.data.model.MediaType
import org.megras.graphstore.MutableQuadSet
import org.megras.graphstore.QuadSet
import org.megras.graphstore.derived.DerivedRelationHandler
import org.megras.graphstore.derived.QuadSetAware
import org.megras.util.Constants
import org.megras.util.FileUtil
import org.megras.util.PdfCropUtil

class FigureHandler(private val quadSet: QuadSet, private val objectStore: FileSystemObjectStore) : DerivedRelationHandler<LocalQuadValue>, QuadSetAware {
    override val predicate: URIValue = getPredicate()
    override val requiresExternalService: Boolean = true  // Depends on DocumentModelJsonHandler which uses gRPC

    private var effectiveQuadSet: QuadSet = quadSet

    override fun setQuadSet(quadSet: QuadSet) {
        this.effectiveQuadSet = quadSet
    }

    companion object {
        fun getPredicate(): URIValue {
            return URIValue("${Constants.DERIVED_PREFIX}/figure")
        }
    }

    override fun canDerive(subject: URIValue): Boolean {
        val osr = FileUtil.getOsr(subject, this.effectiveQuadSet, this.objectStore) ?: return false

        return when (MediaType.mimeTypeMap[osr.descriptor.mimeType]) {
            MediaType.DOCUMENT -> true
            else -> false
        }
    }

    override fun derive(subject: URIValue): Collection<LocalQuadValue> {
        val path = FileUtil.getPath(subject, this.effectiveQuadSet, this.objectStore) ?: return emptyList()

        // Obtain cached Docling JSON via derived relation (will compute-and-cache if missing). Target only the JSON predicate to avoid recursion.
        val docJsonPredicate = DocumentModelJsonHandler.getPredicate()
        val json: String = (effectiveQuadSet
            .filter(listOf(subject), listOf(docJsonPredicate), null)
            .filterPredicate(docJsonPredicate)
            .firstOrNull()?.`object` as? StringValue)?.value ?: ""

        val figures: List<Map<String, Any?>> = try {
            if (json.isBlank()) emptyList() else {
                val mapper = ObjectMapper().registerKotlinModule()
                @Suppress("UNCHECKED_CAST")
                val root = mapper.readValue(json, Map::class.java) as Map<String, Any?>

                (root["pictures"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
            }
        } catch (e: Exception) {
            println("Error parsing figures for '$path': ${e.message}")
            emptyList()
        }

        if (figures.isEmpty()) {
            return emptyList()
        }

        return try {
            PdfCropUtil.storeCrops(
                subject = subject,
                items = figures,
                namePrefix = "figure",
                quadSet = effectiveQuadSet as MutableQuadSet,
                objectStore = objectStore
            )
        } catch (e: Exception) {
            println("Error storing figure crops for '$path': ${e.message}")
            emptyList()
        }
    }
}