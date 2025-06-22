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
import org.megras.graphstore.implicit.handlers.BesideSpatialHandler
import org.megras.graphstore.implicit.handlers.ClipKnnHandler
import org.megras.graphstore.implicit.handlers.ContainsSegmentHandler
import org.megras.graphstore.implicit.handlers.ContainsSpatialHandler
import org.megras.graphstore.implicit.handlers.CoversSpatialHandler
import org.megras.graphstore.implicit.handlers.DisjointSpatialHandler
import org.megras.graphstore.implicit.handlers.EqualsSegmentHandler
import org.megras.graphstore.implicit.handlers.EqualsSpatialHandler
import org.megras.graphstore.implicit.handlers.FinishesSegmentHandler
import org.megras.graphstore.implicit.handlers.IntersectsSpatialHandler
import org.megras.graphstore.implicit.handlers.MeetsSegmentHandler
import org.megras.graphstore.implicit.handlers.OverlapsSegmentHandler
import org.megras.graphstore.implicit.handlers.OverlapsSpatialHandler
import org.megras.graphstore.implicit.handlers.PrecedesSegmentHandler
import org.megras.graphstore.implicit.handlers.SegmentSiblingHandler
import org.megras.graphstore.implicit.handlers.StartsSegmentHandler
import org.megras.graphstore.implicit.handlers.WithinSpatialHandler
import org.megras.graphstore.implicit.handlers.ClipKnnRegexHandler

class ImplicitRelationRegistrar(private val objectStore: FileSystemObjectStore) {
    private val handlers = mutableListOf<ImplicitRelationHandler>()
    private val regexHandlers = mutableListOf<RegexImplicitRelationHandler>()

    init {
//        register(SamePrefixHandler())
        register(ClipNearDuplicateHandler())
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
        register(ContainsSpatialHandler())
        register(EqualsSpatialHandler())
        register(IntersectsSpatialHandler())
        register(WithinSpatialHandler())
        register(CoversSpatialHandler())
        register(BesideSpatialHandler())
        register(DisjointSpatialHandler())
        register(OverlapsSpatialHandler())
        // Register regex handler for clipKnn
        register(ClipKnnRegexHandler())
        register(SegmentSiblingHandler())
    }

    private fun register(objectHandler: ImplicitRelationHandler) {
        handlers.add(objectHandler)
    }

    private fun register(regexHandler: RegexImplicitRelationHandler) {
        regexHandlers.add(regexHandler)
    }

    fun getHandlers(): List<ImplicitRelationHandler> {
        return handlers
    }

    fun getRegexHandlers(): List<RegexImplicitRelationHandler> {
        return regexHandlers
    }
}