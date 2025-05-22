package org.megras.graphstore.implicit

import org.megras.data.fs.FileSystemObjectStore
import org.megras.graphstore.implicit.handlers.SamePrefixHandler
import org.megras.graphstore.implicit.handlers.AfterObjectHandler
import org.megras.graphstore.implicit.handlers.ContainsObjectHandler
import org.megras.graphstore.implicit.handlers.EqualsObjectHandler
import org.megras.graphstore.implicit.handlers.FinishesObjectHandler
import org.megras.graphstore.implicit.handlers.MeetsObjectHandler
import org.megras.graphstore.implicit.handlers.ClipNearDuplicateHandler
import org.megras.graphstore.implicit.handlers.OverlapsObjectHandler
import org.megras.graphstore.implicit.handlers.PrecedesObjectHandler
import org.megras.graphstore.implicit.handlers.StartsObjectHandler

class ImplicitRelationRegistrar(private val objectStore: FileSystemObjectStore) {
    private val ObjectHandlers = mutableListOf<ImplicitRelationHandler>()

    init {
        register(SamePrefixHandler())
        register(ClipNearDuplicateHandler(objectStore))
        register(AfterObjectHandler())
        register(PrecedesObjectHandler())
        register(FinishesObjectHandler())
        register(StartsObjectHandler())
        register(MeetsObjectHandler())
        register(ContainsObjectHandler())
        register(EqualsObjectHandler())
        register(OverlapsObjectHandler())
    }

    private fun register(objectHandler: ImplicitRelationHandler) {
        ObjectHandlers.add(objectHandler)
    }

    fun getObjectHandlers(): List<ImplicitRelationHandler> {
        return ObjectHandlers
    }
}