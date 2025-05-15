package org.megras.graphstore.implicit

import org.megras.graphstore.implicit.handlers.NearDuplicateHandler
import org.megras.graphstore.implicit.handlers.SamePrefixHandler
import org.megras.graphstore.implicit.handlers.temporal.AfterHandler
import org.megras.graphstore.implicit.handlers.temporal.ContainsHandler
import org.megras.graphstore.implicit.handlers.temporal.EqualsHandler
import org.megras.graphstore.implicit.handlers.temporal.FinishesHandler
import org.megras.graphstore.implicit.handlers.temporal.MeetsHandler
import org.megras.graphstore.implicit.handlers.temporal.OverlapsHandler
import org.megras.graphstore.implicit.handlers.temporal.PrecedesHandler
import org.megras.graphstore.implicit.handlers.temporal.StartsHandler

class ImplicitRelationRegistrar {
    private val handlers = mutableListOf<ImplicitRelationHandler>()

    init {
        register(SamePrefixHandler())
        //register(NearDuplicateHandler())
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