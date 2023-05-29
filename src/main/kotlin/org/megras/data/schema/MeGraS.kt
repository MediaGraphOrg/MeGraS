package org.megras.data.schema

import org.megras.data.graph.URIValue

enum class MeGraS(suffix: String) {

    RAW_ID("rawId"),
    MEDIA_TYPE("mediaType"),
    RAW_MIME_TYPE("rawMimeType"),
    CANONICAL_ID("canonicalId"), //raw id of canonical representation
    CANONICAL_MIME_TYPE("canonicalMimeType"),
    FILE_NAME("fileName"),
    SEGMENT_OF("segmentOf"),
    SEGMENT_TYPE("segmentType"),
    SEGMENT_DEFINITION("segmentDefinition"),
    SEGMENT_BOUNDS("bounds"),
    QUERY_DISTANCE("queryDistance"),
    PREVIEW_ID("previewId") //raw id of object preview
    ;

    companion object {
        private const val prefix = "http://megras.org/schema#"
    }

    val uri = URIValue(MeGraS.prefix, suffix)


}