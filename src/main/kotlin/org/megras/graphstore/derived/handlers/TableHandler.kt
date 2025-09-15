package org.megras.graphstore.derived.handlers

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.fs.file.PseudoFile
import org.megras.data.graph.LocalQuadValue
import org.megras.data.graph.LongValue
import org.megras.data.graph.Quad
import org.megras.data.graph.StringValue
import org.megras.data.graph.URIValue
import org.megras.data.model.MediaType
import org.megras.data.schema.MeGraS
import org.megras.graphstore.MutableQuadSet
import org.megras.graphstore.QuadSet
import org.megras.graphstore.derived.DerivedRelationHandler
import org.megras.graphstore.derived.QuadSetAware
import org.megras.segmentation.Bounds
import org.megras.util.Constants
import org.megras.util.FileUtil
import java.io.File
import kotlin.math.max

class TableHandler(
    private val quadSet: QuadSet,
    private val objectStore: FileSystemObjectStore
) : DerivedRelationHandler<LocalQuadValue>, QuadSetAware {

    override val predicate: URIValue = getPredicate()
    private var effectiveQuadSet: QuadSet = quadSet

    override fun setQuadSet(quadSet: QuadSet) {
        this.effectiveQuadSet = quadSet
    }

    companion object {
        fun getPredicate(): URIValue = URIValue("${Constants.DERIVED_PREFIX}/table")
    }

    override fun canDerive(subject: URIValue): Boolean {
        val osr = FileUtil.getOsr(subject, effectiveQuadSet, objectStore) ?: return false
        return when (MediaType.mimeTypeMap[osr.descriptor.mimeType]) {
            MediaType.DOCUMENT -> true
            else -> false
        }
    }

    override fun derive(subject: URIValue): Collection<LocalQuadValue> {
        val path = FileUtil.getPath(subject, effectiveQuadSet, objectStore) ?: return emptyList()

        val json: String = (effectiveQuadSet
            .filter(listOf(subject), listOf(DocumentModelJsonHandler.getPredicate()), null)
            .firstOrNull()?.`object` as? StringValue)?.value ?: ""

        if (json.isBlank()) return emptyList()

        val mapper = ObjectMapper().registerKotlinModule()
        val root: Map<String, Any?>
        try {
            root = mapper.readValue(json)
        } catch (e: Exception) {
            println("Error parsing doc model for '$path': ${e.message}")
            return emptyList()
        }

        val tables = (root["tables"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
        if (tables.isEmpty()) return emptyList()

        val baseName = File(path).nameWithoutExtension.ifBlank { "document" }
        val mqs = (effectiveQuadSet as? MutableQuadSet) ?: return emptyList()
        val created = mutableListOf<LocalQuadValue>()

        // Build lookup map from paragraph self_ref to paragraph id for caption resolution
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

        tables.forEachIndexed { idx, tbl ->
            val selfRef = tbl["self_ref"]?.toString() ?: ""
            val ordinal = selfRef.substringAfterLast("/").toLongOrNull()
            val label = tbl["label"]?.toString()?.ifBlank { null } ?: "table"

            val prov = (tbl["prov"] as? List<*>)?.firstOrNull() as? Map<*, *>
            val pageNo = (prov?.get("page_no") as? Number)?.toInt()
            val bbox = prov?.get("bbox") as? Map<*, *>
            val l = (bbox?.get("l") as? Number)?.toDouble()
            val r = (bbox?.get("r") as? Number)?.toDouble()
            val t = (bbox?.get("t") as? Number)?.toDouble()
            val b = (bbox?.get("b") as? Number)?.toDouble()
            val pageIndex = pageNo?.let { it - 1 } ?: 0

            // Extract caption refs from table entry, similar to PdfCropUtil
            val captionRefs: List<String> = when (val caps = tbl["captions"]) {
                is List<*> -> caps.mapNotNull { cap ->
                    when (cap) {
                        is Map<*, *> -> (cap["\$ref"] as? String)
                        is String -> cap.takeIf { it.startsWith("/") || it.startsWith("#") }
                        else -> null
                    }
                }
                else -> emptyList()
            }

            // Extract cells
            val data = tbl["data"] as? Map<*, *>
            val cells = (data?.get("table_cells") as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
            if (cells.isEmpty()) return@forEachIndexed

            // Compute grid size from end offsets (Docling indices appear 0-based, end-exclusive)
            var rowCount = 0
            var colCount = 0
            cells.forEach { c ->
                rowCount = max(rowCount, (c["end_row_offset_idx"] as? Number)?.toInt() ?: 0)
                colCount = max(colCount, (c["end_col_offset_idx"] as? Number)?.toInt() ?: 0)
            }
            if (rowCount <= 0 || colCount <= 0) return@forEachIndexed

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

            // Store CSV artifact
            val tableOrdinal = ordinal ?: idx.toLong()
            val outName = "${baseName}-page${pageIndex + 1}-table${tableOrdinal}.csv"
            val id = FileUtil.addFile(objectStore, effectiveQuadSet as MutableQuadSet, PseudoFile(csv.toByteArray(Charsets.UTF_8), outName), metaSkip = true)

            // Attach metadata quads
            val metaQuads = mutableListOf(
                Quad(id, MeGraS.SEGMENT_OF.uri, subject),
                Quad(id, URIValue(Constants.NLP_PREFIX + "/label"), StringValue(label)),
                Quad(id, URIValue(Constants.NLP_PREFIX + "/rowCount"), LongValue(rowCount.toLong())),
                Quad(id, URIValue(Constants.NLP_PREFIX + "/colCount"), LongValue(colCount.toLong()))
            )
            if (pageNo != null) {
                metaQuads.add(Quad(id, URIValue(Constants.NLP_PREFIX + "/pageNumber"), LongValue(pageNo.toLong())))
            }
            if (l != null && r != null && t != null && b != null) {
                metaQuads.add(
                    Quad(id, MeGraS.SEGMENT_BOUNDS.uri, StringValue(Bounds("$l,$r,$t,$b,-,-,$pageIndex,$pageIndex").toString())
                    )
                )
            }
            if (selfRef.isNotBlank()) {
                metaQuads.add(Quad(id, URIValue(Constants.NLP_PREFIX + "/reference"), StringValue(selfRef)))
            }
            if (ordinal != null) {
                metaQuads.add(Quad(id, URIValue(Constants.NLP_PREFIX + "/ordinal"), LongValue(ordinal)))
            }

            // Link table CSV to caption paragraph documents, if available
            if (captionRefs.isNotEmpty()) {
                captionRefs.forEach { refStr ->
                    refToParagraph[refStr]?.let { pid ->
                        metaQuads.add(Quad(id, URIValue(Constants.NLP_PREFIX + "/caption"), pid))
                    }
                }
            }

            mqs.addAll(metaQuads)
            created.add(id)
        }

        return created
    }
}