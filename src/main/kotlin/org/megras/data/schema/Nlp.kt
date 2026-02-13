package org.megras.data.schema

import org.megras.data.graph.URIValue

enum class Nlp(suffix: String) {

    PAGE("page"),
    LABEL("label"),
    REFERENCE("reference"),
    ORDINAL("ordinal"),
    ASSET("asset"),
    CAPTION("caption");

    companion object {
        const val PREFIX = "http://megras.org/nlp#"
    }

    val uri = URIValue(Nlp.PREFIX, suffix)
}
