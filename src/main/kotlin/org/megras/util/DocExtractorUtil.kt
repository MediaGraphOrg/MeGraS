package org.megras.util

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.fs.file.PseudoFile
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
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.collections.mutableMapOf
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object DocExtractorUtil {
    private fun getDataFromDocJson(type: String, quadSet: QuadSet, subject: URIValue): List<Map<String, Any?>> {
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

    private fun getParagraphMap(subject: URIValue, quadSet: QuadSet): Map<String, LocalQuadValue> {
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

            var bytes: ByteArray
            var outName: String = "temp.bin"
            when (type) {
                DocExtractorUtilType.FIGURES -> {
                    val dpi: Float = 300f
                    val path = FileUtil.getPath(subject, quadSet, objectStore)!!
                        PDDocument.load(File(path)).use { doc ->
                            val renderer = PDFRenderer(doc)
                            val page = doc.getPage(pageIndex)
                            val mediaBox = page.mediaBox
                            val pageWidthPt = mediaBox.width.toDouble()
                            val pageHeightPt = mediaBox.height.toDouble()
                            val pageImage: BufferedImage = renderer.renderImageWithDPI(pageIndex, dpi)
                            val imgW = pageImage.width
                            val imgH = pageImage.height
                            val scaleX = imgW / pageWidthPt
                            val scaleY = imgH / pageHeightPt
                            val leftPx = ((l - mediaBox.lowerLeftX) * scaleX).roundToInt()
                            val rightPx = ((r - mediaBox.lowerLeftX) * scaleX).roundToInt()
                            val topPx = (imgH - ((t - mediaBox.lowerLeftY) * scaleY)).roundToInt()
                            val bottomPx = (imgH - ((b - mediaBox.lowerLeftY) * scaleY)).roundToInt()
                            val x = max(0, min(leftPx, rightPx))
                            val y = max(0, min(topPx, bottomPx))
                            var w = max(0, kotlin.math.abs(rightPx - leftPx))
                            var h = max(0, kotlin.math.abs(bottomPx - topPx))
                            if (x + w > imgW) w = imgW - x
                            if (y + h > imgH) h = imgH - y
                            val sub = pageImage.getSubimage(x, y, w, h)
                            val baos = ByteArrayOutputStream()
                            ImageIO.write(sub, "PNG", baos)
                            bytes = baos.toByteArray()
                        }
                    outName = "page${pageNo}-figure${ordinal}.png"
                }
                DocExtractorUtilType.TABLES -> {
                    // Extract cells
                    val tbl = item["data"] as? Map<*, *>
                    val cells = (tbl?.get("table_cells") as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
                    // Compute grid size from end offsets (Docling indices appear 0-based, end-exclusive)
                    var rowCount = 0
                    var colCount = 0
                    cells.forEach { c ->
                        rowCount = max(rowCount, (c["end_row_offset_idx"] as? Number)?.toInt() ?: 0)
                        colCount = max(colCount, (c["end_col_offset_idx"] as? Number)?.toInt() ?: 0)
                    }
                    // Build a simple 2D grid; place text at the start offsets (top-left anchor for spans)
                    val grid = Array(rowCount) { Array(colCount) { "" } }
                    cells.forEach { c ->
                        val sr = (c["start_row_offset_idx"] as? Number)?.toInt() ?: 0
                        val sc = (c["start_col_offset_idx"] as? Number)?.toInt() ?: 0
                        val text = c["text"]?.toString() ?: ""
                        if (sr in 0 until rowCount && sc in 0 until colCount) {
                            grid[sr][sc] = when {
                                grid[sr][sc].isBlank() -> text
                                text.isBlank() -> grid[sr][sc]
                                else -> grid[sr][sc] + " " + text
                            }
                        }
                    }
                    // CSV encode
                    fun csvEscape(s: String): String {
                        val needsQuotes = s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')
                        var out = s.replace("\"", "\"\"")
                        if (needsQuotes) out = "\"$out\""
                        return out
                    }
                    val csv = buildString {
                        grid.forEachIndexed { rIdx, row ->
                            row.forEachIndexed { cIdx, cell ->
                                append(csvEscape(cell))
                                if (cIdx < row.lastIndex) append(',')
                            }
                            if (rIdx < grid.lastIndex) append('\n')
                        }
                    }
                    bytes = csv.toByteArray(Charsets.UTF_8)
                    outName = "page${pageNo}-table${ordinal}.csv"
                }
                DocExtractorUtilType.PARAGRAPHS -> {
                    val textStr = (item["text"] as? String)?.ifBlank { null } ?: (item["orig"] as String)
                    bytes = textStr.toByteArray(Charsets.UTF_8)
                    outName = "page${pageNo}-paragraph${ordinal}.txt"
                }
            }
            val assetId = FileUtil.addFile(objectStore, quadSet as MutableQuadSet, PseudoFile(bytes, outName), metaSkip = true)

            val metaQuads = mutableListOf(
                Quad(dataObj, URIValue(Constants.NLP_PREFIX + "/page"), pageObj),
                Quad(dataObj, URIValue(Constants.NLP_PREFIX + "/label"), StringValue(label)),
                Quad(dataObj, URIValue(Constants.NLP_PREFIX + "/reference"), StringValue(reference)),
                Quad(dataObj, URIValue(Constants.NLP_PREFIX + "/ordinal"), LongValue(ordinal)),
                Quad(dataObj, URIValue(Constants.NLP_PREFIX + "/asset"), assetId)
            )
            if (captionRefs.isNotEmpty()) {
                val refToParagraph: Map<String, LocalQuadValue> = getParagraphMap(subject, quadSet)

                captionRefs.forEach { refStr ->
                    refToParagraph[refStr]?.let { pid ->
                        metaQuads.add(Quad(dataObj, URIValue(Constants.NLP_PREFIX + "/caption"), pid))
                    }
                }
            }

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