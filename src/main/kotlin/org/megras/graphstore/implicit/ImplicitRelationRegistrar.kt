package org.megras.graphstore.implicit

import org.megras.data.fs.FileSystemObjectStore
import org.megras.graphstore.implicit.handlers.SamePrefixHandler
import org.megras.graphstore.implicit.handlers.ClipNearDuplicateHandler
import org.megras.graphstore.implicit.handlers.AfterObjectHandler
import org.megras.graphstore.implicit.handlers.ContainsObjectHandler
import org.megras.graphstore.implicit.handlers.EqualsObjectHandler
import org.megras.graphstore.implicit.handlers.FinishesObjectHandler
import org.megras.graphstore.implicit.handlers.MeetsObjectHandler
import org.megras.graphstore.implicit.handlers.OverlapsObjectHandler
import org.megras.graphstore.implicit.handlers.PrecedesObjectHandler
import org.megras.graphstore.implicit.handlers.StartsObjectHandler
import org.megras.graphstore.implicit.handlers.AfterSegmentHandler
import org.megras.graphstore.implicit.handlers.ContainsSegmentHandler
import org.megras.graphstore.implicit.handlers.ContainsSpatialHandler
import org.megras.graphstore.implicit.handlers.EqualsSegmentHandler
import org.megras.graphstore.implicit.handlers.FinishesSegmentHandler
import org.megras.graphstore.implicit.handlers.MeetsSegmentHandler
import org.megras.graphstore.implicit.handlers.OverlapsSegmentHandler
import org.megras.graphstore.implicit.handlers.PrecedesSegmentHandler
import org.megras.graphstore.implicit.handlers.StartsSegmentHandler

class ImplicitRelationRegistrar(private val objectStore: FileSystemObjectStore) {
    private val ObjectHandlers = mutableListOf<ImplicitRelationHandler>()

    init {
        register(SamePrefixHandler())
        register(ClipNearDuplicateHandler(objectStore))
//        register(AfterObjectHandler())
//        register(PrecedesObjectHandler())
//        register(FinishesObjectHandler())
//        register(StartsObjectHandler())
//        register(MeetsObjectHandler())
//        register(ContainsObjectHandler())
//        register(EqualsObjectHandler())
//        register(OverlapsObjectHandler())
//        register(AfterSegmentHandler())
//        register(PrecedesSegmentHandler())
//        register(FinishesSegmentHandler())
//        register(StartsSegmentHandler())
//        register(MeetsSegmentHandler())
//        register(ContainsSegmentHandler())
//        register(EqualsSegmentHandler())
//        register(OverlapsSegmentHandler())
//        register(ContainsSpatialHandler())
    }

    private fun register(objectHandler: ImplicitRelationHandler) {
        ObjectHandlers.add(objectHandler)
    }

    fun getHandlers(): List<ImplicitRelationHandler> {
        return ObjectHandlers
    }
}