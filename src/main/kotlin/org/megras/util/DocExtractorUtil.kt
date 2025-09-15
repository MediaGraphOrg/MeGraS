package org.megras.util

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.megras.data.graph.StringValue
import org.megras.data.graph.URIValue
import org.megras.graphstore.QuadSet
import org.megras.graphstore.derived.handlers.DocumentModelJsonHandler

object DocExtractorUtil {
    fun getDataFromDocJson(type: String, quadSet: QuadSet, subject: URIValue): List<Map<String, Any?>> {
        val json: String = (quadSet
            .filter(listOf(subject), listOf(DocumentModelJsonHandler.getPredicate()), null)
            .firstOrNull()?.`object` as? StringValue)?.value ?: ""

        if (json.isBlank()) return emptyList()

        val data: List<Map<String, Any?>> = try {
            if (json.isBlank()) emptyList() else {
                val mapper = ObjectMapper().registerKotlinModule()
                @Suppress("UNCHECKED_CAST")
                val root = mapper.readValue(json, Map::class.java) as Map<String, Any?>
                (root[type] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
            }
        } catch (e: Exception) {
            println("Error parsing JSON': ${e.message}")
            emptyList()
        }

        return data
    }
}