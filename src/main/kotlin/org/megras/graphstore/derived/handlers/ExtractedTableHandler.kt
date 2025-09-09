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

class ExtractedTableHandler(private val quadSet: QuadSet, private val objectStore: FileSystemObjectStore) : DerivedRelationHandler<LocalQuadValue> {
    override val predicate: URIValue = getPredicate()

    companion object {
        fun getPredicate(): URIValue {
            return URIValue("${Constants.DERIVED_PREFIX}/extractedTable")
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

        val tables: List<Map<String, Any?>> = runBlocking {
            val client = DoclingClient(ServiceConfig.grpcHost, ServiceConfig.grpcPort)
            try {
                val json = client.extractDocJson(path)
                val mapper = ObjectMapper().registerKotlinModule()
                @Suppress("UNCHECKED_CAST")
                val root = mapper.readValue(json, Map::class.java) as Map<String, Any?>

                val textIndex: Map<String, String> = run {
                    val texts = (root["texts"] as? List<*>) ?: emptyList<Any?>()
                    val idx = mutableMapOf<String, String>()
                    texts.forEach { t ->
                        if (t is Map<*, *>) {
                            val selfRef = t["self_ref"] as? String
                            val textStr = (t["text"] as? String) ?: (t["orig"] as? String)
                            if (!selfRef.isNullOrBlank() && !textStr.isNullOrBlank()) {
                                idx[selfRef] = textStr
                            }
                        }
                    }
                    idx
                }

                val tbls = (root["tables"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()

                // Resolve caption refs to strings
                tbls.map { tbl ->
                    val m = tbl.toMutableMap()
                    val caps = (m["captions"] as? List<*>) ?: emptyList<Any?>()
                    val capStrings = caps.mapNotNull { c ->
                        if (c is Map<*, *>) {
                            val ref = c["\$ref"] as? String
                            ref?.let { textIndex[it] }
                        } else c as? String
                    }
                    m["captions"] = capStrings
                    m
                }
            } catch (e: Exception) {
                println("Error extracting tables for '$path': ${e.message}")
                emptyList()
            } finally {
                client.close()
            }
        }

        if (tables.isEmpty()) {
            return emptyList()
        }

        return try {
            PdfCropUtil.storeCrops(
                pdfPath = path,
                items = tables,
                namePrefix = "table",
                quadSet = quadSet as MutableQuadSet,
                objectStore = objectStore
            )
        } catch (e: Exception) {
            println("Error storing table crops for '$path': ${e.message}")
            emptyList()
        }
    }
}
