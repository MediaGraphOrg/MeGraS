package org.megras.util

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.fs.file.PseudoFile
import org.megras.data.graph.LocalQuadValue
import org.megras.data.graph.LongValue
import org.megras.data.graph.Quad
import org.megras.data.graph.StringValue
import org.megras.data.graph.URIValue
import org.megras.data.schema.MeGraS
import org.megras.graphstore.MutableQuadSet
import org.megras.graphstore.derived.handlers.ParagraphHandler
import org.megras.segmentation.Bounds
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object PdfCropUtil {

    /**
     * Store crops for items having prov[0].page_no (1-based) and prov[0].bbox with l,r,t,b in PDF points.
     * The stored files are named: <baseName>-page<page>-<prefix><index>.png
     */
    fun storeCrops(
        subject: URIValue,
        items: List<Map<String, Any?>>, // Each item should contain prov -> [ { page_no, bbox{l,r,t,b} } ]
        namePrefix: String,
        quadSet: MutableQuadSet,
        objectStore: FileSystemObjectStore,
        dpi: Float = 150f
    ): List<LocalQuadValue> {
        val pdfPath = FileUtil.getPath(subject, quadSet, objectStore)!!
        val ids = mutableListOf<LocalQuadValue>()
        val baseName = File(pdfPath).nameWithoutExtension.ifBlank { "document" }

        // Ensure paragraphs are derived and build a lookup from text self_ref -> paragraph id
        val paragraphPredicate = ParagraphHandler.getPredicate()
        val paragraphQuads = quadSet
            .filter(listOf(subject), listOf(paragraphPredicate), null)
        val refToParagraph = mutableMapOf<String, LocalQuadValue>()
        paragraphQuads.forEach { q ->
            val pid = q.`object` as? LocalQuadValue ?: return@forEach
            // find the self_ref stored as NLP_PREFIX/reference on the paragraph id
            val refQuad = quadSet
                .filterSubject(pid)
                .filterPredicate(URIValue(Constants.NLP_PREFIX + "/reference"))
                .firstOrNull()
            val ref = (refQuad?.`object` as? StringValue)?.value
            if (!ref.isNullOrBlank()) {
                refToParagraph[ref] = pid
            }
        }

        PDDocument.load(File(pdfPath)).use { doc ->
            val renderer = PDFRenderer(doc)
            for ((idx, item) in items.withIndex()) {
                val prov = (item["prov"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: continue
                val pageNo = (prov["page_no"] as? Number)?.toInt() ?: continue
                val bbox = prov["bbox"] as? Map<*, *> ?: continue
                val l = (bbox["l"] as? Number)?.toDouble() ?: continue
                val r = (bbox["r"] as? Number)?.toDouble() ?: continue
                val t = (bbox["t"] as? Number)?.toDouble() ?: continue
                val b = (bbox["b"] as? Number)?.toDouble() ?: continue
                // captions can be a list of refs or strings; we resolve only refs to paragraph documents
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

                val pageIndex = pageNo - 1
                if (pageIndex < 0 || pageIndex >= doc.numberOfPages) continue
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

                var x = max(0, min(leftPx, rightPx))
                var y = max(0, min(topPx, bottomPx))
                var w = max(0, kotlin.math.abs(rightPx - leftPx))
                var h = max(0, kotlin.math.abs(bottomPx - topPx))

                if (w == 0 || h == 0) continue
                if (x + w > imgW) w = imgW - x
                if (y + h > imgH) h = imgH - y
                if (w <= 0 || h <= 0) continue

                val sub = try {
                    pageImage.getSubimage(x, y, w, h)
                } catch (_: Exception) {
                    continue
                }

                val baos = ByteArrayOutputStream()
                ImageIO.write(sub, "PNG", baos)
                val bytes = baos.toByteArray()
                val name = "${baseName}-page${pageIndex + 1}-${namePrefix}${idx}.png"
                val pseudo = PseudoFile(bytes, name)
                val id = FileUtil.addFile(objectStore, quadSet, pseudo, metaSkip = true)

                val metaQuads = mutableListOf(
                    Quad(id, MeGraS.SEGMENT_OF.uri, subject),
                    Quad(id, MeGraS.SEGMENT_BOUNDS.uri, StringValue(Bounds("$l,$r,$t,$b,-,-,$pageIndex,$pageIndex").toString())),
                    Quad(id, URIValue(Constants.NLP_PREFIX + "/pageNumber"), LongValue(pageNo.toLong()))
                )

                if (namePrefix.isNotBlank()) {
                    metaQuads.add(Quad(id, URIValue(Constants.NLP_PREFIX + "/label"), StringValue(namePrefix)))
                }
                if (reference.isNotBlank()) {
                    metaQuads.add(Quad(id, URIValue(Constants.NLP_PREFIX + "/reference"), StringValue(reference)))
                }
                if (ordinal != null) {
                    metaQuads.add(Quad(id, URIValue(Constants.NLP_PREFIX + "/ordinal"), LongValue(ordinal)))
                }

                // Link crop to caption paragraph documents, if available
                if (captionRefs.isNotEmpty()) {
                    captionRefs.forEach { refStr ->
                        refToParagraph[refStr]?.let { pid ->
                            metaQuads.add(Quad(id, URIValue(Constants.NLP_PREFIX + "/caption"), pid))
                        }
                    }
                }

                quadSet.addAll(metaQuads)
                ids.add(id)
            }
        }

        return ids
    }
}
