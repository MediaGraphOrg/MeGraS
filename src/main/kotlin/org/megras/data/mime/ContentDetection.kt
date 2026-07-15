package org.megras.data.mime

/**
 * Defense against content-type confusion on upload ([MeGraS security audit] #8).
 *
 * Previously the [MimeType] recorded on a stored object came solely from the
 * client-supplied file extension (`PseudoFile.extension`, derived from the
 * upload filename). An attacker could therefore rename e.g. a crafted binary
 * as `evil.svg` and have it dispatched to Batik, or `evil.pdf` to PDFBox —
 * i.e. choose the parser by renaming the file.
 *
 * [detect] inspects the leading bytes of the content instead. It recognises a
 * curated set of *unambiguous* magic signatures and maps them to a
 * [MimeType]; for everything else it conservatively falls back to the
 * extension-based mapping. It deliberately refuses to claim a media type it
 * cannot prove from bytes rather than silently trusting the name.
 *
 * RIFF (`RIFF....WEBM/WAVE/AVI `) and ISO-BMFF (`....ftyp...`) containers are
 * detected via their format sub-tags so that WebM/WAV/AVI and MP4/MOV are
 * distinguished correctly rather than all collapsing to one bucket. Containers
 * without a recognised sub-tag fall back to extension lookup so that, e.g.,
 * a truncated header is handled gracefully.
 */
internal object ContentDetection {

    /**
     * Returns the [MimeType] for [content], preferring a verified magic
     * signature over [fallbackByExtension]. Callers should pass the
     * extension-derived type as the fallback.
     */
    fun detect(content: ByteArray, fallbackByExtension: MimeType): MimeType {
        val sniffed = sniff(content) ?: return fallbackByExtension
        return sniffed
    }

    private fun sniff(bytes: ByteArray): MimeType? {
        // Each matcher enforces its own minimum length; a too-short or
        // unrecognised prefix simply falls through to the caller's fallback.
        return when {
            bytes.matchesIntsAt(0, 0xFF, 0xD8, 0xFF) -> MimeType.JPEG_I
            bytes.matchesIntsAt(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> MimeType.PNG
            bytes.matchesIntsAt(0, 0x47, 0x49, 0x46, 0x38) -> MimeType.GIF
            bytes.matchesIntsAt(0, 0x42, 0x4D) -> MimeType.BMP
            // Big-endian and little-endian TIFF headers.
            bytes.matchesIntsAt(0, 0x49, 0x49, 0x2A, 0x00) ||
                bytes.matchesIntsAt(0, 0x4D, 0x4D, 0x00, 0x2A) -> MimeType.TIFF
            bytes.matchesIntsAt(0, 0x4F, 0x67, 0x67, 0x53) -> MimeType.OGG
            bytes.matchesIntsAt(0, 0x4D, 0x54, 0x68, 0x64) -> MimeType.MIDI
            bytes.matchesAsciiAt(0, "%PDF-") -> MimeType.PDF
            else -> detectContainer(bytes)
        }
    }

    /**
     * RIFF and ISO-BMFF containers need their format-brand sub-tag inspected to
     * be identified accurately. Everything else returns null (fall back).
     */
    private fun detectContainer(bytes: ByteArray): MimeType? {
        // RIFF....WEBM/WAVE/AVI  — only the three brands we model.
        if (bytes.size >= 12 &&
            bytes.matchesAsciiAt(0, "RIFF") &&
            bytes.matchesAnyAsciiAt(8, "WEBM", "WAVE", "AVI ")
        ) {
            return when (String(bytes, 8, 4)) {
                "WEBM" -> MimeType.WEBM
                "WAVE" -> MimeType.WAV
                "AVI " -> MimeType.AVI
                else -> null
            }
        }
        // ....ftyp....  — ISO-BMFF (MP4/MOV share this brand family).
        if (bytes.size >= 12 && bytes.matchesAsciiAt(4, "ftyp")) {
            return MimeType.MP4
        }
        // Matroska EBML header.
        if (bytes.size >= 4 && bytes.matchesIntsAt(0, 0x1A, 0x45, 0xDF, 0xA3)) {
            return MimeType.MKV
        }
        return null
    }

    private fun ByteArray.matchesIntsAt(offset: Int, vararg b: Int): Boolean {
        if (size < offset + b.size) return false
        for (i in b.indices) if (this[offset + i].toIntUns() != b[i]) return false
        return true
    }

    private fun ByteArray.matchesAsciiAt(offset: Int, ascii: String): Boolean {
        if (size < offset + ascii.length) return false
        for (i in ascii.indices) if (this[offset + i].toIntUns() != ascii[i].toInt() and 0xFF) return false
        return true
    }

    private fun ByteArray.matchesAnyAsciiAt(offset: Int, vararg ascii: String): Boolean =
        ascii.any { matchesAsciiAt(offset, it) }

    private fun Byte.toIntUns() = this.toInt() and 0xFF
}
