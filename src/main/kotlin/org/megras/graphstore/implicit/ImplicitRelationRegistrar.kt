package org.megras.graphstore.implicit

import org.megras.graphstore.implicit.handlers.NearDuplicateHandler
import org.megras.graphstore.implicit.handlers.SamePrefixHandler
import org.megras.graphstore.implicit.handlers.temporal.AfterHandler
import org.megras.graphstore.implicit.handlers.temporal.PrecedesHandler

class ImplicitRelationRegistrar {
    private val handlers = mutableListOf<ImplicitRelationHandler>()

    init {
        register(SamePrefixHandler())
        //register(NearDuplicateHandler())
        register(AfterHandler())
        register(PrecedesHandler())
    }

    private fun register(handler: ImplicitRelationHandler) {
        handlers.add(handler)
    }

    fun getHandlers(): List<ImplicitRelationHandler> {
        return handlers
    }
}