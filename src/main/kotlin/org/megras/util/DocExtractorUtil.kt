package org.megras.util

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.graph.LocalQuadValue
import org.megras.data.graph.LongValue
import org.megras.data.graph.Quad
import org.megras.data.graph.StringValue
import org.megras.data.graph.URIValue
import org.megras.graphstore.MutableQuadSet
import org.megras.graphstore.QuadSet
import org.megras.graphstore.derived.handlers.DocumentModelJsonHandler
import org.megras.graphstore.derived.handlers.ParagraphHandler
import org.megras.segmentation.SegmentationUtil
import org.megras.segmentation.type.Interval
import org.megras.segmentation.type.Page
import org.megras.segmentation.type.Rect
import kotlin.collections.mutableMapOf

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

    fun getParagraphMap(subject: URIValue, quadSet: QuadSet): Map<String, LocalQuadValue> {
        val refToParagraph = mutableMapOf<String, LocalQuadValue>()
        run {
            val paragraphQuads = quadSet.filter(listOf(subject), listOf(ParagraphHandler.getPredicate()), null)
            paragraphQuads.forEach { q ->
                val pid = q.`object` as? LocalQuadValue ?: return@forEach
                val refQuad = quadSet
                    .filter(listOf(pid), listOf(URIValue(Constants.NLP_PREFIX + "/reference")), null)
                    .firstOrNull()
                val ref = (refQuad?.`object` as? StringValue)?.value
                if (!ref.isNullOrBlank()) {
                    refToParagraph[ref] = pid
                }
            }
        }
        return refToParagraph
    }

    fun getQuadsFromDocJson(subject: URIValue, type: DocExtractorUtilType, quadSet: MutableQuadSet, objectStore: FileSystemObjectStore): Collection<LocalQuadValue> {
        val data: List<Map<String, Any?>> = getDataFromDocJson(type.jsonKey, quadSet, subject)

        if (data.isEmpty()) return emptyList()

        val created = mutableListOf<LocalQuadValue>()

        data.forEach { item ->
            val prov = (item["prov"] as List<*>).first() as Map<*, *>
            val pageNo = (prov["page_no"] as Number).toInt()
            val bbox = prov["bbox"] as Map<*, *>
            val l = (bbox["l"] as Number).toDouble()
            val r = (bbox["r"] as Number).toDouble()
            val t = (bbox["t"] as Number).toDouble()
            val b = (bbox["b"] as Number).toDouble()

            val captionRefs: List<String> = when (val caps = item["captions"]) {
                is List<*> -> caps.mapNotNull { cap ->
                    when (cap) {
                        is Map<*, *> -> (cap["\$ref"] as? String)
                        is String -> cap.takeIf { it.startsWith("/") || it.startsWith("#") }
                        else -> null
                    }
                }
                else -> emptyList()
            }
            val reference = item["self_ref"].toString()
            val ordinal = reference.substringAfterLast("/").toLong()
            val label = item["label"].toString()

            // First segment to the relevant page (pageNo - 1), then apply rect
            // TODO: also add page number to page, if it not available yet
            val pageIndex = pageNo - 1
            val pageSeg = Page(listOf(Interval(pageIndex.toDouble(), pageIndex.toDouble())))
            val pageObj = SegmentationUtil.segment(subject, pageSeg, objectStore, quadSet)
            val rectSeg = Rect(l, r, b, t)
            val dataObj = SegmentationUtil.segment(pageObj, rectSeg, objectStore, quadSet)

            val metaQuads = mutableListOf(
                Quad(dataObj, URIValue(Constants.NLP_PREFIX + "/page"), pageObj),
                Quad(dataObj, URIValue(Constants.NLP_PREFIX + "/label"), StringValue(label)),
                Quad(dataObj, URIValue(Constants.NLP_PREFIX + "/reference"), StringValue(reference)),
                Quad(dataObj, URIValue(Constants.NLP_PREFIX + "/ordinal"), LongValue(ordinal))
            )
            if (captionRefs.isNotEmpty()) {
                val refToParagraph: Map<String, LocalQuadValue> = getParagraphMap(subject, quadSet)

                captionRefs.forEach { refStr ->
                    refToParagraph[refStr]?.let { pid ->
                        metaQuads.add(Quad(dataObj, URIValue(Constants.NLP_PREFIX + "/caption"), pid))
                    }
                }
            }

            // TODO: render image for figures

            // TODO: extract table cells for tables

            // TODO: store text for paragraphs

            quadSet.addAll(metaQuads)
            created.add(dataObj)
        }

        return created
    }
}

enum class DocExtractorUtilType(val typeName: String) {
    FIGURES("pictures"),
    TABLES("tables"),
    PARAGRAPHS("texts");

    val jsonKey: String get() = typeName
}