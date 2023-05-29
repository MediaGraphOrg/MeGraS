package org.megras.graphstore

import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.data.graph.VectorValue

interface QuadSet : Set<Quad> {

    /**
     * returns the [Quad] with the specified id in case it is contained within the [QuadSet]
     */
    fun getId(id: Long): Quad?

    /**
     * returns a [QuadSet] only containing the [Quad]s with a specified subject
     */
    fun filterSubject(subject: QuadValue): QuadSet

    /**
     * returns a [QuadSet] only containing the [Quad]s with a specified predicate
     */
    fun filterPredicate(predicate: QuadValue): QuadSet

    /**
     * returns a [QuadSet] only containing the [Quad]s with a specified object
     */
    fun filterObject(`object`: QuadValue): QuadSet

    /**
     * returns a [QuadSet] only containing subjects, predicates, and objects specified in the supplied collections.
     * null serves as 'any' selector
     */
    fun filter(subjects: Collection<QuadValue>?, predicates: Collection<QuadValue>?, objects: Collection<QuadValue>?): QuadSet

    fun toMutable(): MutableQuadSet

    fun toSet(): Set<Quad>

    operator fun plus(other: QuadSet): QuadSet

    fun nearestNeighbor(predicate: QuadValue, `object`: VectorValue, count: Int, distance: Distance): QuadSet

    fun textFilter(predicate: QuadValue, objectFilterText: String): QuadSet

}