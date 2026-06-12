package org.megras.data.schema

import org.megras.data.graph.URIValue

private const val MEGRAS_PREFIX = "http://megras.org/schema#"

enum class MeGraS(suffix: String) {

    RAW_ID("rawId"),
    MEDIA_TYPE("mediaType"),
    RAW_MIME_TYPE("rawMimeType"),
    CANONICAL_ID("canonicalId"), //raw id of canonical representation
    CANONICAL_MIME_TYPE("canonicalMimeType"),
    FILE_NAME("fileName"),
    BOUNDS("bounds"),
    SEGMENT_OF("segmentOf"),
    SEGMENT_TYPE("segmentType"),
    SEGMENT_DEFINITION("segmentDefinition"),
    SEGMENT_BOUNDS("segmentBounds"),
    QUERY_DISTANCE("queryDistance"),
    PREVIEW_ID("previewId") //raw id of object preview
    ;

    val uri = URIValue(MEGRAS_PREFIX, suffix)


}