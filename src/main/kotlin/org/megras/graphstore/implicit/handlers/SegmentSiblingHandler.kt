package org.megras.graphstore.implicit.handlers

import org.megras.data.graph.Quad
import org.megras.data.graph.URIValue
import org.megras.data.schema.MeGraS
import org.megras.graphstore.BasicQuadSet
import org.megras.graphstore.QuadSet
import org.megras.graphstore.implicit.ImplicitRelationHandler
import org.megras.graphstore.implicit.ImplicitRelationMutableQuadSet
import org.megras.util.Constants
import java.util.concurrent.ConcurrentHashMap

class SegmentSiblingHandler : ImplicitRelationHandler {

    override val predicate: URIValue = URIValue("${Constants.IMPLICIT_PREFIX}/segmentSibling")

    private lateinit var quadSet: ImplicitRelationMutableQuadSet

    override fun init(quadSet: ImplicitRelationMutableQuadSet) {
        this.quadSet = quadSet
    }

    // same function because relationship is symmetric
    private fun findValues(objectId: URIValue): Set<URIValue> {
        val parent = this.quadSet.filter(
            setOf(objectId),
            setOf(MeGraS.SEGMENT_OF.uri),
            null
        ).firstOrNull()?.`object` ?: return emptySet()
        return this.quadSet
            .filter(null, setOf(MeGraS.SEGMENT_OF.uri), setOf(parent))
            .mapNotNull { it.subject as? URIValue }
            .filter { it != objectId }
            .toSet()
    }

    override fun findObjects(subject: URIValue): Set<URIValue> {
        return findValues(subject)
    }

    override fun findSubjects(`object`: URIValue): Set<URIValue> {
        return findValues(`object`)
    }

    override fun findAll(): QuadSet {
        // 1. Bulk fetch all parent relationships in one query
        val parentQuads = quadSet.filter(null, setOf(MeGraS.SEGMENT_OF.uri), null)

        // 2. Group subjects by their parent URI in-memory
        val subjectsByParent = parentQuads
            .mapNotNull { quad ->
                val subject = quad.subject as? URIValue
                val parent = quad.`object` as? URIValue
                if (subject != null && parent != null) {
                    parent to subject
                } else {
                    null
                }
            }
            .groupBy({ it.first }, { it.second })

        val resultingQuads = ConcurrentHashMap.newKeySet<Quad>()

        // 3. Process each group of siblings in parallel
        subjectsByParent.values.parallelStream().forEach { siblings ->
            if (siblings.size < 2) return@forEach

            // More efficient loop to generate pairs
            val siblingList = siblings.toList()
            for (i in siblingList.indices) {
                for (j in (i + 1) until siblingList.size) {
                    val s1 = siblingList[i]
                    val s2 = siblingList[j]

                    // Add symmetric relationship
                    resultingQuads.add(Quad(s1, predicate, s2))
                    resultingQuads.add(Quad(s2, predicate, s1))
                }
            }
        }

        return BasicQuadSet(resultingQuads)
    }
}