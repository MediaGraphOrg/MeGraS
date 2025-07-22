package org.megras.segmentation

import org.megras.data.graph.URIValue
import org.megras.data.schema.MeGraS
import org.megras.graphstore.MutableQuadSet

object BoundsUtil {
    fun getBounds(subject: URIValue, quads: MutableQuadSet) : Bounds? {
        val boundsString = (quads.filter(setOf(subject), setOf(MeGraS.BOUNDS.uri), null).firstOrNull()
            ?: throw IllegalArgumentException("Invalid subject. No segmentation found."))
            .`object`.toString()

        return Bounds(boundsString.replace("^^String", ""))
    }
}