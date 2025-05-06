package org.megras.lang.sparql

import org.apache.jena.sparql.function.FunctionRegistry
import org.megras.lang.sparql.functions.CLIP_TEXT
import org.megras.util.Constants

class FunctionRegistrar {

    companion object {
        fun register() {
            FunctionRegistry.get().put("${Constants.SPARQL_PREFIX}#CLIP_TEXT", CLIP_TEXT::class.java)
        }
    }
}