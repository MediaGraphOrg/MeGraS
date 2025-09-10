package org.megras.graphstore.derived.handlers

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
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

        // Post-process JSON: resolve caption $ref to actual strings using text index
        val mapper = ObjectMapper().registerKotlinModule()
        try {
            @Suppress("UNCHECKED_CAST")
            val root = mapper.readValue(rawJson, MutableMap::class.java) as MutableMap<String, Any?>

            // Build text index: self_ref -> text
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

            fun resolveCaptionsList(caps: List<*>): List<String> = caps.mapNotNull { c ->
                when (c) {
                    is String -> c
                    is Map<*, *> -> {
                        val ref = c["\$ref"] as? String
                        ref?.let { textIndex[it] }
                    }
                    else -> null
                }
            }

            // Resolve for pictures
            (root["pictures"] as? List<*>)?.let { pics ->
                val newPics = pics.mapNotNull { it as? Map<String, Any?> }.map { pic ->
                    val m = pic.toMutableMap()
                    val caps = (m["captions"] as? List<*>) ?: emptyList<Any?>()
                    m["captions"] = resolveCaptionsList(caps)
                    m
                }
                root["pictures"] = newPics
            }

            // Resolve for tables
            (root["tables"] as? List<*>)?.let { tabs ->
                val newTabs = tabs.mapNotNull { it as? Map<String, Any?> }.map { tbl ->
                    val m = tbl.toMutableMap()
                    val caps = (m["captions"] as? List<*>) ?: emptyList<Any?>()
                    m["captions"] = resolveCaptionsList(caps)
                    m
                }
                root["tables"] = newTabs
            }

            val processedJson = mapper.writeValueAsString(root)
            return listOf(StringValue(processedJson))
        } catch (e: Exception) {
            // If post-processing fails, fall back to raw JSON
            println("Warning: Failed to post-process Docling JSON for '$path': ${e.message}")
            return listOf(StringValue(rawJson))
        }
    }
}
