package org.megras.graphstore

import org.megras.util.knn.CosineDistance
import org.megras.util.knn.Distance
import org.vitrivr.cottontail.client.language.basics.Distances

enum class Distance {

    COSINE{
        override fun cottontail() = Distances.COSINE
        override fun distance(): Distance = CosineDistance
    }

    ;

    abstract fun cottontail(): Distances
    abstract fun distance(): Distance

}