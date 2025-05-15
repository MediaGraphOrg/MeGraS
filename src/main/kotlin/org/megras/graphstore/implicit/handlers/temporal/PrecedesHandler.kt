package org.megras.graphstore.implicit.handlers.temporal

import org.megras.data.graph.TemporalValue
import org.megras.data.graph.URIValue
import org.megras.util.Constants

class PrecedesHandler : ImplicitTemporalHandler(
    predicate = URIValue("${Constants.TEMPORAL_PREFIX}/precedes"),
    compare = { _, end1: TemporalValue?, start2: TemporalValue?, _ ->
        end1 != null && start2 != null && end1 < start2
    }
)