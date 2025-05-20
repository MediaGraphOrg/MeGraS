package org.megras.lang.sparql

import org.apache.jena.sparql.function.FunctionRegistry
import org.megras.data.fs.FileSystemObjectStore
import org.megras.graphstore.MutableQuadSet
import org.megras.lang.sparql.functions.ClipTextFunction
import org.megras.lang.sparql.functions.CosineSimFunction
import org.megras.lang.sparql.functions.accessors.temporal.CreatedAccessor
import org.megras.lang.sparql.functions.accessors.temporal.EndAccessor
import org.megras.lang.sparql.functions.accessors.temporal.StartAccessor
import org.megras.util.Constants

class FunctionRegistrar {

    companion object {
        fun register(quadSet: MutableQuadSet, objectStore: FileSystemObjectStore) {
            // * Custom SPARQL functions
            FunctionRegistry.get().put("${Constants.SPARQL_PREFIX}#CLIP_TEXT", ClipTextFunction::class.java)
            FunctionRegistry.get().put("${Constants.SPARQL_PREFIX}#COSINE_SIM", CosineSimFunction::class.java)

            // * mm functions
            // ** Temporal accessors
            FunctionRegistry.get().put("${Constants.MM_PREFIX}#START", StartAccessor::class.java)
            StartAccessor.setQuads(quadSet)
            FunctionRegistry.get().put("${Constants.MM_PREFIX}#END", EndAccessor::class.java)
            EndAccessor.setQuads(quadSet)
            FunctionRegistry.get().put("${Constants.MM_PREFIX}#CREATED", CreatedAccessor::class.java)
            CreatedAccessor.setQuadsAndOs(quadSet, objectStore)
        }
    }
}