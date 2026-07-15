package org.megras.util

import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.pdmodel.PDDocument
import java.awt.image.BufferedImage
import java.io.IOException
import java.io.InputStream
import javax.imageio.ImageIO

/**
 * Bounds media decoding against decompression/"pixel-bomb" and malformed-document
 * denial-of-service. Every place MeGraS decodes attacker-reachable image or PDF
 * content routes through these helpers so that oversized inputs surface as a
 * predictable [IOException] instead of exhausting the heap.
 *
 * The image helper reads the format header only (dimensions) before allocating
 * any pixel buffer, so a tiny compressed image claiming enormous dimensions is
 * rejected without being inflated. The PDF helper backs parsing with a temp
 * file (rather than the heap) and caps the page count.
 *
 * Callers keep their existing exception-handling semantics (swallow-and-return
 * vs. propagate); this object only makes "too big" look like a normal decode
 * failure.
 */
internal object SafeDecoding {

    /** Reject raster images whose decoded pixel count exceeds this (~100 MP). */
    private const val MAX_IMAGE_PIXELS = 100_000_000L

    /** Reject PDFs with more than this many pages. */
    private const val MAX_PDF_PAGES = 500

    /**
     * Decodes a raster image from [stream], first verifying its dimensions do
     * not exceed [MAX_IMAGE_PIXELS]. Dimensions are read from the header before
     * the full pixel buffer is allocated.
     *
     * @throws IOException if the stream is unsupported, malformed, or oversized
     */
    @Throws(IOException::class)
    fun readImage(stream: InputStream): BufferedImage {
        val input = ImageIO.createImageInputStream(stream)
            ?: throw IOException("unsupported image stream")
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) {
            input.close()
            throw IOException("no image reader for stream")
        }
        val reader = readers.next()
        reader.input = input
        try {
            val width = reader.getWidth(0).toLong()
            val height = reader.getHeight(0).toLong()
            if (width <= 0 || height <= 0 || width * height > MAX_IMAGE_PIXELS) {
                throw IOException("rejected oversized image (${width}x${height}); limit $MAX_IMAGE_PIXELS px")
            }
            return reader.read(0)
        } finally {
            reader.dispose()
            input.close()
        }
    }

    /**
     * Loads a PDF from [stream] using temp-file-backed parsing (avoids heap
     * blow-up) and rejects documents exceeding [MAX_PDF_PAGES].
     *
     * @throws IOException if the document is malformed or oversized
     */
    @Throws(IOException::class)
    fun loadPdf(stream: InputStream): PDDocument {
        val doc = PDDocument.load(stream, MemoryUsageSetting.setupTempFileOnly())
        return validateOrClose(doc)
    }

    /**
     * As [loadPdf] for a [java.io.File]. Provided so the file-based call sites
     * are bounded by the same policy as the stream-based ones.
     */
    @Throws(IOException::class)
    fun loadPdf(file: java.io.File): PDDocument {
        val doc = PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())
        return validateOrClose(doc)
    }

    private fun validateOrClose(doc: PDDocument): PDDocument {
        try {
            if (doc.numberOfPages > MAX_PDF_PAGES) {
                throw IOException("rejected oversized pdf (${doc.numberOfPages} pages); limit $MAX_PDF_PAGES")
            }
            return doc
        } catch (e: Throwable) {
            // On rejection (or any failure after load) make sure the document is
            // closed; on success the caller owns the resource.
            try { doc.close() } catch (_: Throwable) {}
            throw e
        }
    }
}
