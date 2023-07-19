package org.megras.segmentation.media

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.megras.segmentation.Bounds
import org.megras.segmentation.type.*
import org.megras.util.AddFileUtil
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.imageio.ImageIO

object DocumentSegmenter {

    private val logger = LoggerFactory.getLogger(this.javaClass)

    fun segment(inputStream: InputStream, segmentation: Segmentation): SegmentationResult? {
        return try {
            val pdf = PDDocument.load(inputStream)
            val newPdf = when (segmentation) {
                is Page -> segmentPages(pdf, segmentation)
                is Rect -> segmentRect(pdf, segmentation)
                is TwoDimensionalSegmentation,
                is ThreeDimensionalSegmentation -> segmentShape(pdf, segmentation)
                else -> {
                    logger.warn("Segmentation type '${segmentation.getType()}' not applicable to PDF")
                    null
                }
            } ?: return null

            val out = ByteArrayOutputStream()
            newPdf.save(out)
            newPdf.close()
            pdf.close()

            val page = pdf.getPage(0)

            SegmentationResult(out.toByteArray(),
                Bounds().addX(0, AddFileUtil.ptToMm(page.mediaBox.width))
                    .addY(0, AddFileUtil.ptToMm(page.mediaBox.height)).addT(0, pdf.numberOfPages))
        } catch (e: Exception) {
            logger.error("Error while segmenting PDF: ${e.localizedMessage}")
            null
        }
    }

    private fun segmentPages(pdf: PDDocument, page: Page): PDDocument {
        (0 until pdf.numberOfPages).reversed().forEach { pageNumber ->
            val keep = page.intervals.any { pageNumber >= it.low && pageNumber <= it.high }
            if (!keep) {
                pdf.removePage(pageNumber)
            }
        }
        return pdf
    }

    private fun segmentRect(pdf: PDDocument, rect: Rect): PDDocument {
        for (i in 0 until pdf.numberOfPages) {
            val page = pdf.getPage(i)
            page.cropBox = PDRectangle(
                rect.xmin.toFloat(), rect.ymin.toFloat(), rect.width.toFloat(), rect.height.toFloat()
            )
        }
        return pdf
    }

    private fun segmentShape(pdf: PDDocument, segmentation: Segmentation): PDDocument? {
        val newPdf = PDDocument()
        val pdfRenderer = PDFRenderer(pdf)

        for (i in 0 until pdf.numberOfPages) {
            // turn into image
            val page = pdfRenderer.renderImage(i, 1F, ImageType.ARGB)

            val seg = when (segmentation) {
                is Rotoscope -> segmentation.slice(i.toDouble())
                is MeshBody -> segmentation.slice(i.toDouble())
                else -> segmentation
            } ?: return null

            // segment image
            val segmentedPage = ImageSegmenter.segment(page, seg, BufferedImage.TYPE_4BYTE_ABGR) ?: return null
            val out = ByteArrayOutputStream()
            ImageIO.write(segmentedPage, "png", out)

            // add a pdf page
            val newPage = PDPage(PDRectangle(segmentedPage.width.toFloat(), segmentedPage.height.toFloat()))
            newPdf.addPage(newPage)

            // draw image onto pdf page
            val img = PDImageXObject.createFromByteArray(newPdf, out.toByteArray(), "$i")
            val contentStream = PDPageContentStream(newPdf, newPage)
            contentStream.drawImage(img, 0f, 0f)
            contentStream.close()
        }
        return newPdf
    }
}