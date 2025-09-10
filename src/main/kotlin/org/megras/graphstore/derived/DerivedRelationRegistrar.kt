package org.megras.graphstore.derived

import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.graph.QuadValue
import org.megras.graphstore.QuadSet
import org.megras.graphstore.derived.handlers.AverageColorHandler
import org.megras.graphstore.derived.handlers.ClipEmbeddingHandler
import org.megras.graphstore.derived.handlers.TextHandler
import org.megras.graphstore.derived.handlers.OcrHandler
import org.megras.graphstore.derived.handlers.PageHandler
import org.megras.graphstore.derived.handlers.FigureHandler
import org.megras.graphstore.derived.handlers.TableHandler
import org.megras.graphstore.derived.handlers.DocumentModelJsonHandler

class DerivedRelationRegistrar(private val quads: QuadSet, private val objectStore: FileSystemObjectStore) {
    private val handlers = mutableListOf<DerivedRelationHandler<QuadValue>>()

    init {
        register(AverageColorHandler(quads, objectStore) as DerivedRelationHandler<QuadValue>)
        register(ClipEmbeddingHandler(quads, objectStore) as DerivedRelationHandler<QuadValue>)
        register(OcrHandler(quads, objectStore) as DerivedRelationHandler<QuadValue>)
        register(PageHandler(quads, objectStore) as DerivedRelationHandler<QuadValue>)
        register(TextHandler(quads, objectStore) as DerivedRelationHandler<QuadValue>)
        register(DocumentModelJsonHandler(quads, objectStore) as DerivedRelationHandler<QuadValue>)
        register(FigureHandler(quads, objectStore) as DerivedRelationHandler<QuadValue>)
        register(TableHandler(quads, objectStore) as DerivedRelationHandler<QuadValue>)
    }

    private fun register(handler: DerivedRelationHandler<QuadValue>) {
        handlers.add(handler)
    }

    fun getHandlers(): List<DerivedRelationHandler<QuadValue>> {
        return handlers
    }
}