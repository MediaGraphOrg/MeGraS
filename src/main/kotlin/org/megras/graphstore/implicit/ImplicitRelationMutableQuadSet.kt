package org.megras.graphstore.implicit

import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.data.graph.URIValue
import org.megras.data.graph.VectorValue
import org.megras.graphstore.BasicQuadSet
import org.megras.graphstore.Distance
import org.megras.graphstore.MutableQuadSet
import org.megras.graphstore.QuadSet

class ImplicitRelationMutableQuadSet(private val base: MutableQuadSet, handlers: Collection<ImplicitRelationHandler>) : MutableQuadSet {

    private val handlers = handlers.associateBy { it.predicate }

    init {
        handlers.forEach { it.init(this) }
    }


    override fun getId(id: Long): Quad? = base.getId(id)



    override fun filterSubject(subject: QuadValue): QuadSet {

        val existing = base.filterSubject(subject)

        if (subject !is URIValue) {
            return existing
        }

        val implicit = handlers.flatMap { (predicate, handler) ->
            val objects = handler.findObjects(subject)
            objects.map { Quad(subject, predicate, it) }
        }.toSet()

        return existing + BasicQuadSet(implicit)
    }

    override fun filterPredicate(predicate: QuadValue): QuadSet {
        return if (handlers.containsKey(predicate)) {
            handlers[predicate]!!.findAll()
        } else {
            base.filterPredicate(predicate)
        }
    }

    override fun filterObject(`object`: QuadValue): QuadSet {

        val existing = base.filterObject(`object`)

        if (`object` !is URIValue) {
            return existing
        }

        val implicit = handlers.flatMap { (predicate, handler) ->
            val subjects = handler.findSubjects(`object`)
            subjects.map { Quad(it, predicate, `object`) }
        }.toSet()

        return existing + BasicQuadSet(implicit)
    }

    override fun filter(
        subjects: Collection<QuadValue>?,
        predicates: Collection<QuadValue>?,
        objects: Collection<QuadValue>?
    ): QuadSet {
        TODO("Not yet implemented")
    }

    override fun toMutable(): MutableQuadSet = this

    override fun toSet(): Set<Quad> {
        TODO("Not yet implemented")
    }

    override fun plus(other: QuadSet): QuadSet {
        TODO("Not yet implemented")
    }

    override fun nearestNeighbor(
        predicate: QuadValue,
        `object`: VectorValue,
        count: Int,
        distance: Distance,
        invert: Boolean
    ): QuadSet {
        TODO("Not yet implemented")
    }

    override fun textFilter(
        predicate: QuadValue,
        objectFilterText: String
    ): QuadSet {
        TODO("Not yet implemented")
    }

    override val size: Int
        get() = TODO("Not yet implemented")

    override fun contains(element: Quad): Boolean {
        TODO("Not yet implemented")
    }

    override fun containsAll(elements: Collection<Quad>): Boolean = elements.all { this.contains(it) }

    override fun isEmpty(): Boolean {
        TODO("Not yet implemented")
    }

    override fun add(element: Quad): Boolean {
        if (handlers.containsKey(element.predicate)) {
            return false
        }
        return this.base.add(element)
    }

    override fun addAll(elements: Collection<Quad>): Boolean {
        TODO("Not yet implemented")
    }

    override fun clear() = base.clear()

    override fun iterator(): MutableIterator<Quad> {
        TODO("Not yet implemented")
    }

    override fun remove(element: Quad): Boolean = base.remove(element)

    override fun removeAll(elements: Collection<Quad>): Boolean = base.removeAll(elements)

    override fun retainAll(elements: Collection<Quad>): Boolean = base.retainAll(elements)
}