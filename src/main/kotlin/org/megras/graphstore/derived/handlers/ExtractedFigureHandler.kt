package org.megras.graphstore.derived.handlers

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.fs.file.PseudoFile
import org.megras.data.graph.LocalQuadValue
import org.megras.data.graph.URIValue
import org.megras.data.model.MediaType
import org.megras.graphstore.MutableQuadSet
import org.megras.graphstore.QuadSet
import org.megras.graphstore.derived.DerivedRelationHandler
import org.megras.util.Constants
import org.megras.util.FileUtil
import org.megras.util.ServiceConfig
import org.megras.util.services.DoclingClient
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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

        // Use DoclingClient to extract figures; then render crops and store them.
        val figures: List<Map<String, Any?>> = runBlocking {
            val client = DoclingClient(ServiceConfig.grpcHost, ServiceConfig.grpcPort)
            try {
                val figs = client.extractFiguresAsMaps(path)
                val mapper = ObjectMapper().registerKotlinModule()
                val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(figs)
                figs
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
            storeFigureCrops(path, figures)
        } catch (e: Exception) {
            println("Error storing figure crops for '$path': ${e.message}")
            emptyList()
        }
    }

    private fun storeFigureCrops(pdfPath: String, figures: List<Map<String, Any?>>): List<LocalQuadValue> {
        val ids = mutableListOf<LocalQuadValue>()
        val baseName = File(pdfPath).nameWithoutExtension.ifBlank { "document" }

        PDDocument.load(File(pdfPath)).use { doc ->
            val renderer = PDFRenderer(doc)
            for ((idx, fig) in figures.withIndex()) {
                val prov = (fig["prov"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: continue
                val pageNo = (prov["page_no"] as? Number)?.toInt() ?: continue // 1-based
                val bbox = prov["bbox"] as? Map<*, *> ?: continue
                val l = (bbox["l"] as? Number)?.toDouble() ?: continue
                val r = (bbox["r"] as? Number)?.toDouble() ?: continue
                val t = (bbox["t"] as? Number)?.toDouble() ?: continue
                val b = (bbox["b"] as? Number)?.toDouble() ?: continue

                val pageIndex = pageNo - 1
                if (pageIndex < 0 || pageIndex >= doc.numberOfPages) continue
                val page = doc.getPage(pageIndex)
                val mediaBox = page.mediaBox
                val pageWidthPt = mediaBox.width.toDouble()
                val pageHeightPt = mediaBox.height.toDouble()

                val dpi = 150f
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
                } catch (e: Exception) {
                    println("Skipping invalid crop (x=$x, y=$y, w=$w, h=$h) for page ${pageIndex + 1}: ${e.message}")
                    continue
                }

                val baos = ByteArrayOutputStream()
                ImageIO.write(sub, "PNG", baos)
                val bytes = baos.toByteArray()
                val name = "${baseName}-page${pageIndex + 1}-figure${idx}.png"
                val pseudo = PseudoFile(bytes, name)
                val id = FileUtil.addFile(objectStore, quadSet as MutableQuadSet, pseudo, metaSkip = true)
                ids.add(id)
            }
        }

        return ids
    }
}