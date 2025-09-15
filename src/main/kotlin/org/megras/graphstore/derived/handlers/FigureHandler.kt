package org.megras.graphstore.derived.handlers

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.graph.LocalQuadValue
import org.megras.data.graph.LongValue
import org.megras.data.graph.Quad
import org.megras.data.graph.StringValue
import org.megras.data.graph.URIValue
import org.megras.data.model.MediaType
import org.megras.graphstore.MutableQuadSet
import org.megras.graphstore.QuadSet
import org.megras.graphstore.derived.DerivedRelationHandler
import org.megras.graphstore.derived.QuadSetAware
import org.megras.segmentation.SegmentationUtil
import org.megras.segmentation.type.Interval
import org.megras.segmentation.type.Page
import org.megras.segmentation.type.Polygon
import org.megras.util.Constants
import org.megras.util.FileUtil

class FigureHandler(
    private val quadSet: QuadSet,
    private val objectStore: FileSystemObjectStore
) : DerivedRelationHandler<LocalQuadValue>, QuadSetAware {

    override val predicate: URIValue = getPredicate()

    private var effectiveQuadSet: QuadSet = quadSet

    override fun setQuadSet(quadSet: QuadSet) {
        this.effectiveQuadSet = quadSet
    }

    companion object {
        fun getPredicate(): URIValue = URIValue("${Constants.DERIVED_PREFIX}/figure")
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

        if (figures.isEmpty()) return emptyList()

        val mqs = effectiveQuadSet as? MutableQuadSet ?: return emptyList()

        // Build lookup from paragraph self_ref to paragraph id for caption links
        val refToParagraph = mutableMapOf<String, LocalQuadValue>()
        run {
            val paragraphPredicate = ParagraphHandler.getPredicate()
            val paragraphQuads = effectiveQuadSet.filter(listOf(subject), listOf(paragraphPredicate), null)
            paragraphQuads.forEach { q ->
                val pid = q.`object` as? LocalQuadValue ?: return@forEach
                val refQuad = effectiveQuadSet
                    .filterSubject(pid)
                    .filterPredicate(URIValue(Constants.NLP_PREFIX + "/reference"))
                    .firstOrNull()
                val ref = (refQuad?.`object` as? StringValue)?.value
                if (!ref.isNullOrBlank()) {
                    refToParagraph[ref] = pid
                }
            }
        }

        val created = mutableListOf<LocalQuadValue>()

        figures.forEach { item ->
            // Assume prov is always present
            val prov = (item["prov"] as List<*>).first() as Map<*, *>
            val pageNo = (prov["page_no"] as Number).toInt()
            val bbox = prov["bbox"] as Map<*, *>
            val lRaw = (bbox["l"] as Number).toDouble()
            val rRaw = (bbox["r"] as Number).toDouble()
            val tRaw = (bbox["t"] as Number).toDouble()
            val bRaw = (bbox["b"] as Number).toDouble()

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
            val reference = item["self_ref"]?.toString() ?: ""
            val ordinal = reference.substringAfterLast("/").toLongOrNull()

            // Bottom-left origin
            val xmin = minOf(lRaw, rRaw)
            val xmax = maxOf(lRaw, rRaw)
            val ymin = minOf(bRaw, tRaw)
            val ymax = maxOf(bRaw, tRaw)

            val pts = listOf(
                xmin to ymin,
                xmax to ymin,
                xmax to ymax,
                xmin to ymax
            )
            val polySeg = Polygon(pts)

            // First segment to the relevant page (pageNo - 1), then apply polygon
            val pageIndex = pageNo - 1
            val pageSeg = Page(listOf(Interval(pageIndex.toDouble(), pageIndex.toDouble())))
            val pageObj = SegmentationUtil.segment(subject, pageSeg, objectStore, mqs)
            val figureObj = SegmentationUtil.segment(pageObj, polySeg, objectStore, mqs)

            val metaQuads = mutableListOf(
                Quad(figureObj, URIValue(Constants.NLP_PREFIX + "/pageNumber"), LongValue(pageNo.toLong())),
                Quad(figureObj, URIValue(Constants.NLP_PREFIX + "/label"), StringValue("figure"))
            )
            if (reference.isNotBlank()) {
                metaQuads.add(Quad(figureObj, URIValue(Constants.NLP_PREFIX + "/reference"), StringValue(reference)))
            }
            if (ordinal != null) {
                metaQuads.add(Quad(figureObj, URIValue(Constants.NLP_PREFIX + "/ordinal"), LongValue(ordinal)))
            }
            if (captionRefs.isNotEmpty()) {
                captionRefs.forEach { refStr ->
                    refToParagraph[refStr]?.let { pid ->
                        metaQuads.add(Quad(figureObj, URIValue(Constants.NLP_PREFIX + "/caption"), pid))
                    }
                }
            }

            mqs.addAll(metaQuads)
            created.add(figureObj)
        }

        return created
    }
}