package org.megras.graphstore

import org.megras.data.graph.DoubleValue
import org.megras.data.graph.LongValue
import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.data.graph.VectorValue
import org.megras.id.SemanticId

/**
 * Identifies which component of a quad (subject, predicate, or object) is used for ordering.
 */
enum class QuadComponent { SUBJECT, PREDICATE, OBJECT }

/**
 * Specifies an ordering key for [QuadSet.orderedFilter].
 * @param component Which quad component to sort by.
 * @param ascending true for ASC, false for DESC.
 */
data class OrderSpec(
    val component: QuadComponent,
    val ascending: Boolean
)

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

    /**
     * Filter, sort, and limit quads in a single operation.
     * Backends like [org.megras.graphstore.db.PostgresStore] override this to push
     * ORDER BY / LIMIT / OFFSET into SQL, avoiding full materialisation.
     *
     * The default implementation delegates to [filter] and sorts in memory.
     *
     * @param subjects  null = any subject, empty = no match.
     * @param predicates null = any predicate, empty = no match.
     * @param objects    null = any object, empty = no match.
     * @param orderBy    sort keys applied left-to-right (first = most significant).
     * @param limit      max results to return (use [Int.MAX_VALUE] for no limit).
     * @param offset     number of leading results to skip.
     */
    fun orderedFilter(
        subjects: Collection<QuadValue>?,
        predicates: Collection<QuadValue>?,
        objects: Collection<QuadValue>?,
        orderBy: List<OrderSpec>,
        limit: Int = Int.MAX_VALUE,
        offset: Int = 0
    ): QuadSet {
        // Default: filter then sort in memory
        var result = filter(subjects, predicates, objects).toList()

        // Apply each sort key (most-significant first)
        for (spec in orderBy) {
            val keyExtractor: (Quad) -> Comparable<*> = { quad ->
                when (spec.component) {
                    QuadComponent.SUBJECT -> quad.subject.toString()
                    QuadComponent.PREDICATE -> quad.predicate.toString()
                    QuadComponent.OBJECT -> quad.`object`.toString()
                }
            }
            result = if (spec.ascending) {
                result.sortedWith(compareBy(keyExtractor))
            } else {
                result.sortedWith(compareByDescending(keyExtractor))
            }
        }

        return BasicQuadSet(result.drop(offset).take(limit).toSet())
    }

}
