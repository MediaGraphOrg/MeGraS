package org.megras.graphstore

import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.data.graph.StringValue
import org.megras.data.graph.VectorValue

class HybridMutableQuadSet(private val base: MutableQuadSet, private val knn: MutableQuadSet) : MutableQuadSet {

    companion object {
        private val knownVectorPredicates = setOf(
            QuadValue.of("<http://lsc.dcu.ie/feature#mmr>"),
            QuadValue.of("<http://lsc.dcu.ie/feature#openclip>"),
            QuadValue.of("<http://lsc.dcu.ie/feature#openai_embed>")
        )
    }

    override fun getId(id: Long): Quad? = base.getId(id)

    override fun filterSubject(subject: QuadValue): QuadSet{
        return base.filterSubject(subject) + knn.filter(setOf(subject), knownVectorPredicates, null)
    }

    override fun filterPredicate(predicate: QuadValue): QuadSet {
        return if (predicate in knownVectorPredicates) knn.filterPredicate(predicate) else base.filterPredicate(predicate)
    }

    override fun filterObject(`object`: QuadValue): QuadSet {
        return base.filterObject(`object`) + knn.filter(null, knownVectorPredicates, setOf(`object`))
    }

    override fun filter(
        subjects: Collection<QuadValue>?,
        predicates: Collection<QuadValue>?,
        objects: Collection<QuadValue>?
    ): QuadSet {

        if (predicates == null) {
            return base.filter(subjects, predicates, objects) + knn.filter(subjects, predicates, objects)
        }

        val basePredicates = predicates - knownVectorPredicates
        val knnPredicates = predicates intersect knownVectorPredicates

        if (basePredicates.isEmpty()) {
            return knn.filter(subjects, knnPredicates, objects)
        }

        if (knnPredicates.isEmpty()) {
            return base.filter(subjects, basePredicates, objects)
        }

        return base.filter(subjects, basePredicates, objects) + knn.filter(subjects, knnPredicates, objects)
    }

    override fun toMutable(): MutableQuadSet = this

    override fun toSet(): Set<Quad> = this

    override fun plus(other: QuadSet): QuadSet {
        TODO("Not yet implemented")
    }

    override fun nearestNeighbor(predicate: QuadValue, `object`: VectorValue, count: Int, distance: Distance, invert: Boolean): QuadSet = knn.nearestNeighbor(predicate, `object`, count, distance, invert)

    override fun textFilter(predicate: QuadValue, objectFilterText: String): QuadSet = base.textFilter(predicate, objectFilterText)

    override val size: Int
        get() = base.size

    override fun contains(element: Quad): Boolean = base.contains(element)

    override fun containsAll(elements: Collection<Quad>): Boolean = base.containsAll(elements)

    override fun isEmpty(): Boolean = base.isEmpty()

    override fun iterator(): MutableIterator<Quad> = base.iterator()

    override fun add(element: Quad): Boolean {
        return base.add(element) or if(element.subject is VectorValue || element.`object` is VectorValue /*|| element.subject is StringValue || element.`object` is StringValue*/) {
            knn.add(element)
        } else false
    }

    override fun addAll(elements: Collection<Quad>): Boolean {
        return base.addAll(elements) or knn.addAll(elements.filter { it.subject is VectorValue || it.`object` is VectorValue /*|| it.subject is StringValue || it.`object` is StringValue*/})
    }

    override fun clear() {
        base.clear()
        knn.clear()
    }

    override fun remove(element: Quad): Boolean {
        return base.remove(element) or knn.remove(element)
    }

    override fun removeAll(elements: Collection<Quad>): Boolean {
        return base.removeAll(elements) or knn.removeAll(elements)
    }

    override fun retainAll(elements: Collection<Quad>): Boolean {
        return base.retainAll(elements) or knn.retainAll(elements)
    }
}