package org.megras.graphstore.implicit.handlers.temporal

import org.megras.data.graph.TemporalValue
import org.megras.data.graph.URIValue
import org.megras.util.Constants

class MeetsHandler : ImplicitTemporalHandler(
    predicate = URIValue("${Constants.TEMPORAL_PREFIX}/meets"),
    compare = { start1: TemporalValue?, end1: TemporalValue?, start2: TemporalValue?, end2: TemporalValue? ->
        (start1 != null && end2 != null && start1 == end2) || (end1 != null && start2 != null && end1 == start2)
    }
)
