package org.megras.api.rest.data


enum class Distance { COSINE }

data class ApiKnnQuery (val predicate: String, val `object`: List<Double>, val count: Int, val distance: Distance)
