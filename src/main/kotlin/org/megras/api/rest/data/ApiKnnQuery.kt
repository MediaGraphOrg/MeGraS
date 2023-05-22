package org.megras.api.rest.data

data class ApiKnnQuery (val predicate: String, val `object`: List<Double>, val count: Int, val distance: String)
