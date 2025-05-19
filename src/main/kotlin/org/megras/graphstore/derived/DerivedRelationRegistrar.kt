package org.megras.graphstore.derived

import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.graph.QuadValue
import org.megras.graphstore.QuadSet
import org.megras.graphstore.derived.handlers.AverageColorHandler
import org.megras.graphstore.derived.handlers.ClipEmbeddingHandler

class DerivedRelationRegistrar(private val quads: QuadSet, private val objectStore: FileSystemObjectStore) {
    private val handlers = mutableListOf<DerivedRelationHandler<QuadValue>>()

    init {
        register(AverageColorHandler(quads, objectStore) as DerivedRelationHandler<QuadValue>)
        register(ClipEmbeddingHandler(quads, objectStore) as DerivedRelationHandler<QuadValue>)
    }

    private fun register(handler: DerivedRelationHandler<QuadValue>) {
        handlers.add(handler)
    }

    fun getHandlers(): List<DerivedRelationHandler<QuadValue>> {
        return handlers
    }
}