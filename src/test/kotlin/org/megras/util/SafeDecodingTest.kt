package org.megras.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.CRC32
import javax.imageio.ImageIO

/**
 * Validates the bounds enforced by [SafeDecoding]. The dimension guard is
 * exercised with a hand-built PNG whose IHDR advertises enormous dimensions but
 * carries no pixel data; the guard must reject it from the header alone, before
 * any pixel buffer is allocated.
 */
class SafeDecodingTest {

    @Test
    fun readsValidImage() {
        val img = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
        val bytes = baosOf { ImageIO.write(img, "PNG", it) }
        val out = SafeDecoding.readImage(ByteArrayInputStream(bytes))
        assertEquals(1, out.width)
        assertEquals(1, out.height)
    }

    @Test
    fun rejectsNonImage() {
        assertThrows(IOException::class.java) {
            SafeDecoding.readImage(ByteArrayInputStream("not an image".toByteArray()))
        }
    }

    @Test
    fun rejectsOversizedImageHeaderWithoutAllocatingPixels() {
        // A PNG whose IHDR claims 50_000x50_000 (2.5 GP) but has no IDAT data.
        // The guard must reject this from the header; we never decode pixels.
        val bytes = pngHeader(width = 50_000, height = 50_000)
        val ex = assertThrows(IOException::class.java) {
            SafeDecoding.readImage(ByteArrayInputStream(bytes))
        }
        assertTrue(
            ex.message?.contains("oversized") == true,
            "expected oversize rejection, got: ${ex.message}"
        )
    }

    @Test
    fun rejectsMalformedPdf() {
        assertThrows(IOException::class.java) {
            SafeDecoding.loadPdf(ByteArrayInputStream("not a pdf".toByteArray()))
        }
    }

    // ---- helpers -------------------------------------------------------------

    private fun baosOf(write: (java.io.OutputStream) -> Unit): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        write(out)
        return out.toByteArray()
    }

    /**
     * Builds PNG signature + a single IHDR chunk with the given dimensions and
     * no image data. CRC is computed correctly so a PNG reader will parse the
     * header (and thereby expose the advertised width/height to the guard).
     */
    private fun pngHeader(width: Int, height: Int): ByteArray {
        val sig = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte())
        val type = "IHDR".toByteArray()
        val data = java.io.ByteArrayOutputStream()
        writeInt(data, width)
        writeInt(data, height)
        data.writeAsByte(8) // bit depth
        data.writeAsByte(0) // colour type (grayscale)
        data.writeAsByte(0) // compression
        data.writeAsByte(0) // filter
        data.writeAsByte(0) // interlace
        val dataBytes = data.toByteArray()

        val crc = CRC32()
        crc.update(type)
        crc.update(dataBytes)

        val out = java.io.ByteArrayOutputStream()
        out.write(sig)
        writeInt(out, dataBytes.size)
        out.write(type)
        out.write(dataBytes)
        writeInt(out, crc.value.toInt())
        return out.toByteArray()
    }

    private fun writeInt(out: java.io.OutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun java.io.OutputStream.writeAsByte(v: Int) = this.write(v)
}
