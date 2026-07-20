package org.megras.graphstore.derived

import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.data.graph.URIValue
import org.megras.graphstore.MutableQuadSet
import org.slf4j.LoggerFactory

class DerivedRelationIngester(
    private val handlers: List<DerivedRelationHandler<QuadValue>>,
    private val quads: MutableQuadSet
) {
    private val logger = LoggerFactory.getLogger(DerivedRelationIngester::class.java)

    fun deriveAll(subject: URIValue): List<Quad> {
        val results = mutableListOf<Quad>()
        for (handler in handlers) {
            if (!handler.canDerive(subject)) continue
            try {
                val objects = handler.derive(subject)
                for (obj in objects) {
                    results.add(Quad(subject, handler.predicate, obj))
                }
            } catch (e: Exception) {
                logger.warn("Derivation failed for ${handler.predicate}: ${e.message}")
            }
        }
        if (results.isNotEmpty()) {
            quads.addAll(results)
        }
        return results
    }
}
