package org.megras.data.schema

import org.megras.data.graph.URIValue

private const val NLP_PREFIX = "http://megras.org/nlp#"

enum class Nlp(suffix: String) {

    PAGE("page"),
    LABEL("label"),
    REFERENCE("reference"),
    ORDINAL("ordinal"),
    ASSET("asset"),
    CAPTION("caption");


    val uri = URIValue(NLP_PREFIX, suffix)
}
