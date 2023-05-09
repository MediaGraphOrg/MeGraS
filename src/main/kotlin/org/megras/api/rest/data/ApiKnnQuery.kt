package org.megras.api.rest.data

import org.megras.data.graph.VectorValue

data class ApiKnnQuery (val predicate: String, val `object`: VectorValue, val count: Int, val distance: String)