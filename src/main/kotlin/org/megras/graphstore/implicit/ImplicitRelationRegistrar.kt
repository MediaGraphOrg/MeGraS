package org.megras.graphstore.implicit

import org.megras.data.fs.FileSystemObjectStore
import org.megras.graphstore.implicit.handlers.SamePrefixHandler
import org.megras.graphstore.implicit.handlers.AfterHandler
import org.megras.graphstore.implicit.handlers.ContainsHandler
import org.megras.graphstore.implicit.handlers.EqualsHandler
import org.megras.graphstore.implicit.handlers.FinishesHandler
import org.megras.graphstore.implicit.handlers.MeetsHandler
import org.megras.graphstore.implicit.handlers.ClipNearDuplicateHandler
import org.megras.graphstore.implicit.handlers.OverlapsHandler
import org.megras.graphstore.implicit.handlers.PrecedesHandler
import org.megras.graphstore.implicit.handlers.StartsHandler

class ImplicitRelationRegistrar(private val objectStore: FileSystemObjectStore) {
    private val handlers = mutableListOf<ImplicitRelationHandler>()

    init {
        register(SamePrefixHandler())
        register(ClipNearDuplicateHandler(objectStore))
        register(AfterHandler())
        register(PrecedesHandler())
        register(FinishesHandler())
        register(StartsHandler())
        register(MeetsHandler())
        register(ContainsHandler())
        register(EqualsHandler())
        register(OverlapsHandler())
    }

    private fun register(handler: ImplicitRelationHandler) {
        handlers.add(handler)
    }

    fun getHandlers(): List<ImplicitRelationHandler> {
        return handlers
    }
}