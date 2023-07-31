package org.megras.data.model

import org.megras.data.mime.MimeType

enum class MediaType(val prefix: Char, val mimeTypes: Set<MimeType>) {

    TEXT('t', setOf(MimeType.CSS, MimeType.CSV, MimeType.HTML, MimeType.JS, MimeType.JSON, MimeType.YAML, MimeType.TEXT)),
    IMAGE('i', setOf(MimeType.BMP, MimeType.GIF, MimeType.JPEG_I, MimeType.PNG, MimeType.SVG, MimeType.TIFF)),
    AUDIO('a', setOf(MimeType.AAC, MimeType.ADP, MimeType.AIF, MimeType.AU, MimeType.MPEG_A, MimeType.MIDI, MimeType.MP4_A, MimeType.OGG_A, MimeType.WAV, MimeType.WAX, MimeType.WMA, MimeType.WEBM_A)),
    VIDEO('v', setOf(MimeType.MOV, MimeType.MP4, MimeType.OGG, MimeType.WEBM, MimeType.MKV)),
    DOCUMENT('d', setOf(MimeType.PDF)),
    MESH('m', setOf(MimeType.OBJ)),
    UNKNOWN('x', setOf(MimeType.OCTET_STREAM));


    companion object {

        val mimeTypeMap = values().flatMap { t ->
            t.mimeTypes.map { it to t }
        }.toMap()

        val prefixMap = values().associateBy { it.prefix }

    }

}