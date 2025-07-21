package org.megras.lang.sparql

import org.apache.jena.sparql.function.FunctionRegistry
import org.megras.graphstore.MutableQuadSet
import org.megras.lang.sparql.functions.ClipTextFunction
import org.megras.lang.sparql.functions.CosineSimFunction
import org.megras.lang.sparql.functions.DayOfWeekFunction
import org.megras.util.Constants

class FunctionRegistrar {

    companion object {
        fun register(quadSet: MutableQuadSet) {
            // * Custom SPARQL functions
            FunctionRegistry.get().put("${Constants.SPARQL_PREFIX}#CLIP_TEXT", ClipTextFunction::class.java)
            FunctionRegistry.get().put("${Constants.SPARQL_PREFIX}#COSINE_SIM", CosineSimFunction::class.java)
            FunctionRegistry.get().put("${Constants.SPARQL_PREFIX}#DAYOFWEEK", DayOfWeekFunction::class.java)

            // * mm functions
        }
    }
}