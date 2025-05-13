package org.megras.graphstore.implicit

import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.data.graph.URIValue
import org.megras.data.graph.VectorValue
import org.megras.graphstore.BasicQuadSet
import org.megras.graphstore.Distance
import org.megras.graphstore.MutableQuadSet
import org.megras.graphstore.QuadSet
import kotlin.collections.containsKey
import kotlin.collections.get

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
        if (subjects.isNullOrEmpty() && objects.isNullOrEmpty() && predicates.isNullOrEmpty()) { //if subjects, predicates, and objects are null, do not filter
                return base
        }

        // check if handlers contain any of the predicates
        val implicitPredicates = predicates?.filter { handlers.containsKey(it) }?.toSet()
        if (implicitPredicates.isNullOrEmpty()) {
            return base.filter(subjects, predicates, objects)
        }
        val nonImplicitPredicates = predicates - implicitPredicates

        val set = mutableSetOf<Quad>()
        if (subjects.isNullOrEmpty() && objects.isNullOrEmpty()) {
            // only filtering by predicates
            implicitPredicates.forEach { predicate ->
                val handler = handlers[predicate]!!
                val quads = handler.findAll()
                set.addAll(quads)
            }
        } else {
            if (!subjects.isNullOrEmpty()) {
                // only filtering by subjects
                implicitPredicates.forEach { predicate ->
                    val handler = handlers[predicate]!!
                    subjects.forEach { subject ->
                        // check if the subject is a URIValue
                        if (subject is URIValue) {
                            val iObjects = handler.findObjects(subject)
                            iObjects.forEach { `object` ->
                                set.add(Quad(subject, predicate, `object`))
                            }
                        }
                    }
                }
            }
            if (!objects.isNullOrEmpty()) {
                // only filtering by objects
                implicitPredicates.forEach { predicate ->
                    val handler = handlers[predicate]!!
                    objects.forEach { `object` ->
                        // check if the subject is a URIValue
                        if (`object` is URIValue) {
                            val iSubjects = handler.findSubjects(`object`)
                            iSubjects.forEach { subject ->
                                set.add(Quad(subject, predicate, `object`))
                            }
                        }
                    }
                }
            }
        }

        val nonImplicitQuads = if (nonImplicitPredicates.isEmpty()) BasicQuadSet() else base.filter(subjects, nonImplicitPredicates, objects)
        return BasicQuadSet(set) + nonImplicitQuads
    }

    override fun toMutable(): MutableQuadSet = this

    override fun toSet(): Set<Quad> = base.toSet()

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