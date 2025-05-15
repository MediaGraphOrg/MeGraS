package org.megras.graphstore.implicit.handlers.temporal

import org.megras.data.graph.TemporalValue
import org.megras.data.graph.URIValue
import org.megras.util.Constants

class AfterHandler : ImplicitTemporalHandler(
    predicate = URIValue("${Constants.TEMPORAL_PREFIX}/after"),
    compare = { start1: TemporalValue?, _, _, end2: TemporalValue? ->
        start1 != null && end2 != null && start1 >= end2
    }
)