package org.megras.data.schema

import org.megras.data.graph.URIValue

private const val SCHEMA_ORG_PREFIX = "http://schema.org/"

enum class SchemaOrg(private val suffix: String) {

    SAME_AS("sameAs"),
    SHA256("sha256")

    ;


    val uri = URIValue(SCHEMA_ORG_PREFIX, suffix)

}