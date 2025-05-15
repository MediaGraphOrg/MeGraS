package org.megras.graphstore.implicit.handlers.temporal

import org.megras.data.graph.TemporalValue
import org.megras.data.graph.URIValue
import org.megras.util.Constants

class OverlapsHandler : ImplicitTemporalHandler(
    predicate = URIValue("${Constants.TEMPORAL_PREFIX}/overlaps"),
    compare = { start1: TemporalValue?, end1: TemporalValue?, start2: TemporalValue?, end2: TemporalValue? ->
        start1 != null && end1 != null && start2 != null && end2 != null &&
        ((start1 < start2 && start2 < end1 && end1 < end2) || (start2 < start1 && start1 < end2 && end2 < end1))
    }
)
