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
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.imageio.ImageIO

object DocumentSegmenter {

    fun segment(inputStream: InputStream, segmentation: Segmentation): SegmentationResult? {
        try {
            val pdf = PDDocument.load(inputStream)
            val newPdf = when (segmentation) {
                is Page -> segmentPages(pdf, segmentation)
                is Rect -> segmentRect(pdf, segmentation)
                is TwoDimensionalSegmentation,
                is ThreeDimensionalSegmentation -> segmentShape(pdf, segmentation)
                else -> null
            } ?: return null

            val out = ByteArrayOutputStream()
            newPdf.save(out)
            newPdf.close()
            pdf.close()

            val page = pdf.getPage(0)
            return SegmentationResult(out.toByteArray(),
                Bounds().addX(0, AddFileUtil.ptToMm(page.mediaBox.width))
                    .addY(0, AddFileUtil.ptToMm(page.mediaBox.height)).addT(0, pdf.numberOfPages))
        } catch (e: Exception) {
            //TODO log
            return null
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
            val page = pdfRenderer.renderImage(i, 1F, ImageType.ARGB)

            val seg = when (segmentation) {
                is Rotoscope -> segmentation.slice(i.toDouble())
                is MeshBody -> segmentation.slice(i.toDouble())
                else -> segmentation
            } ?: return null

            val segmentedPage = ImageSegmenter.segment(page, seg, BufferedImage.TYPE_4BYTE_ABGR) ?: return null
            val out = ByteArrayOutputStream()
            ImageIO.write(segmentedPage, "png", out)

            val newPage = PDPage(PDRectangle(segmentedPage.width.toFloat(), segmentedPage.height.toFloat()))
            newPdf.addPage(newPage)
            val img = PDImageXObject.createFromByteArray(newPdf, out.toByteArray(), "$i")
            val contentStream = PDPageContentStream(newPdf, newPage)
            contentStream.drawImage(img, 0f, 0f)
            contentStream.close()
        }
        return newPdf
    }
}