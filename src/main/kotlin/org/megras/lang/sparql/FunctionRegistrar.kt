package org.megras.lang.sparql

import org.apache.jena.sparql.function.FunctionRegistry
import org.megras.lang.sparql.functions.CLIP_TEXT

class FunctionRegistrar {

    companion object {
        fun register() {
            FunctionRegistry.get().put("http://megras.org/sparql#CLIP_TEXT", CLIP_TEXT::class.java)
        }
    }
}