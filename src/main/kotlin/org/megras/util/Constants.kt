package org.megras.util

object Constants {
    const val BASE_URI = "http://megras.org"
    const val SPARQL_SEGMENT = "sparql"
    const val MM_SEGMENT = "mm"
    const val IMPLICIT_SEGMENT = "implicit"
    const val TEMPORAL_SEGMENT = "temporal"
    const val OBJECT_SEGMENT = "object"
    const val SEGMENT_SEGMENT = "segment"
    const val DERIVED_SEGMENT = "derived"

    const val SPARQL_PREFIX = "$BASE_URI/$SPARQL_SEGMENT"
    const val MM_PREFIX = "$BASE_URI/$SPARQL_SEGMENT/$MM_SEGMENT"
    const val IMPLICIT_PREFIX = "$BASE_URI/$IMPLICIT_SEGMENT"
    const val TEMPORAL_OBJECT_PREFIX = "$BASE_URI/$IMPLICIT_SEGMENT/$TEMPORAL_SEGMENT/$OBJECT_SEGMENT"
    const val DERIVED_PREFIX = "$BASE_URI/$DERIVED_SEGMENT"
}