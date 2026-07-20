package org.megras.graphstore

import org.megras.data.graph.DoubleValue
import org.megras.data.graph.LongValue
import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.data.graph.VectorValue
import org.megras.id.SemanticId

interface QuadSet : Set<Quad> {

    /**
     * Returns the [Quad] whose semantic id (content hash of (s,p,o), accessed
     * via [org.megras.id.id]) equals [id], or null if no such quad is in this
     * set. The id is storage-independent; backends satisfy this from a
     * reverse index keyed on the semantic id. Distributed backends broadcast
     * (the id is content-based, so at most one distinct quad matches across
     * shards).
     */
    fun getId(id: SemanticId): Quad?

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

    fun nearestNeighbor(predicate: QuadValue, `object`: VectorValue, count: Int, distance: Distance, invert: Boolean = false): QuadSet

    fun textFilter(predicate: QuadValue, objectFilterText: String): QuadSet

    /**
     * Returns the set of distinct object values for the given predicate.
     * This is optimized to avoid fetching full quads when only distinct objects are needed.
     */
    fun distinctObjects(predicate: QuadValue): Set<QuadValue> = filterPredicate(predicate).map { it.`object` }.toSet()

    /**
     * Returns the set of distinct subject values for the given predicate.
     * This is optimized to avoid fetching full quads when only distinct subjects are needed.
     */
    fun distinctSubjects(predicate: QuadValue): Set<QuadValue> = filterPredicate(predicate).map { it.subject }.toSet()

    /**
     * Check if at least one quad exists matching subject and predicate.
     * More efficient than filter(...).isNotEmpty() for DB backends.
     */
    fun exists(subject: QuadValue, predicate: QuadValue): Boolean {
        return filter(setOf(subject), setOf(predicate), null).iterator().hasNext()
    }

    /**
     * Filter quads by predicate where the object is a numeric value within [min, max] range.
     * Pass null for min or max for unbounded range on that side.
     */
    fun filterRange(predicate: QuadValue, min: Double?, max: Double?): QuadSet {
        return filterPredicate(predicate).filter { quad ->
            val obj = quad.`object`
            val value = when (obj) {
                is DoubleValue -> obj.value
                is LongValue -> obj.value.toDouble()
                else -> return@filter false
            }
            (min == null || value >= min) && (max == null || value <= max)
        }.toSet().let { BasicQuadSet(it) }
    }

    /**
     * Filter quads by predicate where the object is NOT in the excluded set.
     */
    fun filterNotIn(predicate: QuadValue, excludedValues: Collection<QuadValue>): QuadSet {
        val excludedSet = excludedValues.toSet()
        return filterPredicate(predicate).filter { it.`object` !in excludedSet }
            .toSet().let { BasicQuadSet(it) }
    }

}