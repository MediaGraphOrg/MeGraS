package org.megras.data.schema

import org.megras.data.graph.URIValue

enum class SchemaOrg(private val suffix: String) {

    SAME_AS("sameAs"),
    SHA256("sha256")

    ;
    companion object {
        private const val prefix = "http://schema.org/"
    }

    val uri = URIValue(SchemaOrg.prefix, suffix)

}