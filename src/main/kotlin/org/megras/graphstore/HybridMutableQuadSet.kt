package org.megras.graphstore

import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.data.graph.StringValue
import org.megras.data.graph.VectorValue

class HybridMutableQuadSet(private val base: MutableQuadSet, private val knn: MutableQuadSet) : MutableQuadSet {
    override fun getId(id: Long): Quad? = base.getId(id)

    override fun filterSubject(subject: QuadValue): QuadSet = base.filterSubject(subject)

    override fun filterPredicate(predicate: QuadValue): QuadSet = base.filterPredicate(predicate)

    override fun filterObject(`object`: QuadValue): QuadSet = base.filterObject(`object`)

    override fun filter(
        subjects: Collection<QuadValue>?,
        predicates: Collection<QuadValue>?,
        objects: Collection<QuadValue>?
    ): QuadSet = base.filter(subjects, predicates, objects)

    override fun toMutable(): MutableQuadSet = this

    override fun toSet(): Set<Quad> = this

    override fun plus(other: QuadSet): QuadSet {
        TODO("Not yet implemented")
    }

    override fun nearestNeighbor(predicate: QuadValue, `object`: VectorValue, count: Int, distance: Distance): QuadSet = knn.nearestNeighbor(predicate, `object`, count, distance)

    override fun textFilter(predicate: QuadValue, objectFilterText: String): QuadSet = knn.textFilter(predicate, objectFilterText)

    override val size: Int
        get() = base.size

    override fun contains(element: Quad): Boolean = base.contains(element)

    override fun containsAll(elements: Collection<Quad>): Boolean = base.containsAll(elements)

    override fun isEmpty(): Boolean = base.isEmpty()

    override fun iterator(): MutableIterator<Quad> = base.iterator()

    override fun add(element: Quad): Boolean {
        return base.add(element) or if(element.subject is VectorValue || element.`object` is VectorValue || element.subject is StringValue || element.`object` is StringValue) {
            knn.add(element)
        } else false
    }

    override fun addAll(elements: Collection<Quad>): Boolean {
        return base.addAll(elements) or knn.addAll(elements.filter { it.subject is VectorValue || it.`object` is VectorValue || it.subject is StringValue || it.`object` is StringValue})
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