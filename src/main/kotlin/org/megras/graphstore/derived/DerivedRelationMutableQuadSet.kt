package org.megras.graphstore.derived

import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.data.graph.StringValue
import org.megras.data.graph.URIValue
import org.megras.data.graph.VectorValue
import org.megras.graphstore.BasicQuadSet
import org.megras.graphstore.Distance
import org.megras.graphstore.MutableQuadSet
import org.megras.graphstore.QuadSet

class DerivedRelationMutableQuadSet(private val base: MutableQuadSet, handlers: Collection<DerivedRelationHandler<*>>) :
    MutableQuadSet {

    private val handlers = handlers.associateBy { it.predicate }

    override fun getId(id: Long): Quad? = base.getId(id)

    override fun filterSubject(subject: QuadValue): QuadSet {

        val existing = this.base.filterSubject(subject)

        if (subject !is URIValue) {
            return existing
        }

        val relevantHandlers = handlers.values.filter { handler -> handler.canDerive(subject) }

        val derived = relevantHandlers.mapNotNull { handler ->
            //check if already present
            if (existing.filterPredicate(handler.predicate)
                    .isNotEmpty()
            ) { //TODO a hasPredicate method would be more efficient
                return@mapNotNull null
            }
            val obj = handler.derive(subject)
            if (obj != null) {
                Quad(subject, handler.predicate, obj)
            } else {
                null
            }
        }

        //store derived values for future use
        if (derived.isNotEmpty()) {
            base.addAll(derived)
            return existing + BasicQuadSet(derived.toSet())
        }

        return existing
    }

    private fun getAllBySubject(): Map<URIValue, Collection<Quad>> =
        this.base.iterator().asSequence().filter { it.subject is URIValue }.groupBy { it.subject as URIValue }


    override fun filterPredicate(predicate: QuadValue): QuadSet {

        val existing = this.base.filterPredicate(predicate)

        val handler = this.handlers[predicate]

        if (handler == null) { //nothing to derive
            return existing
        }

        val subjects = getAllBySubject()

        val derived = subjects.mapNotNull { (subject, quads) ->
            if (!handler.canDerive(subject)) {
                return@mapNotNull null
            }
            if (quads.any { it.predicate == predicate }) { //already exists
                return@mapNotNull null
            }
            val obj = handler.derive(subject)
            if (obj != null) {
                Quad(subject, handler.predicate, obj)
            } else {
                null
            }
        }

        //store derived values for future use
        if (derived.isNotEmpty()) {
            base.addAll(derived)
            return existing + BasicQuadSet(derived.toSet())
        }

        return existing


    }

    override fun filterObject(`object`: QuadValue): QuadSet =
        this.base.filterObject(`object`) //derivation never starts at the result

    override fun filter(
        subjects: Collection<QuadValue>?,
        predicates: Collection<QuadValue>?,
        objects: Collection<QuadValue>?
    ): QuadSet {

        val existing = this.base.filter(subjects, predicates, objects)

        val relevantHandlers = predicates?.mapNotNull { this.handlers[it] } ?: this.handlers.values

        if (relevantHandlers.isEmpty()) {
            return existing
        }

        val subs = (subjects ?: existing.map { it.subject }.toSet()).filterIsInstance<URIValue>()
        val derived = subs.flatMap { subject ->
            val present = existing.filterSubject(subject)
            relevantHandlers.mapNotNull { handler ->
                if (present.filterPredicate(handler.predicate).isNotEmpty()) {
                    return@mapNotNull null
                }
                if (!handler.canDerive(subject)) {
                    return@mapNotNull null
                }
                val obj = handler.derive(subject)
                if (obj != null) {
                    Quad(subject, handler.predicate, obj)
                } else {
                    null
                }
            }
        }

        if (derived.isNotEmpty()) {
            base.addAll(derived)
            return existing + BasicQuadSet(derived.toSet())
        }

        return existing

    }

    override fun toMutable(): MutableQuadSet = this

    override fun toSet(): Set<Quad> {
        TODO("Not yet implemented")
    }

    override fun plus(other: QuadSet): QuadSet = this.base.plus(other) //TODO reason about semantics

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

        val existing = this.base.textFilter(predicate, objectFilterText)

        val handler = this.handlers[predicate] ?: return existing

        val subjects = getAllBySubject()

        val derived = subjects.mapNotNull { (subject, quads) ->
            if (!handler.canDerive(subject)) {
                return@mapNotNull null
            }
            if (quads.any { it.predicate == predicate }) { //already exists
                return@mapNotNull null
            }
            val obj = handler.derive(subject)
            if (obj != null) {
                Quad(subject, handler.predicate, obj)
            } else {
                null
            }
        }

        if (derived.isNotEmpty()) {
            base.addAll(derived)
        }

        return existing + BasicQuadSet(derived.filter {
            it.`object` is StringValue && it.`object`.value.contains(
                objectFilterText
            )
        }.toSet())

    }

    override val size: Int
        get() = base.size //technically not correct, but actual size is unknown

    override fun contains(element: Quad): Boolean {
        if (this.base.contains(element)) {
            return true
        }
        if (element.subject !is URIValue) {
            return false
        }
        val handler = this.handlers[element.predicate] ?: return false
        if (!handler.canDerive(element.subject)) {
            return false
        }
        val obj = handler.derive(element.subject)
        if (obj == null) {
            return false
        }
        if (element.`object` == obj) {
            this.base.add(element)
            return true
        }
        return false
    }

    override fun containsAll(elements: Collection<Quad>): Boolean = elements.all { contains(it) }

    override fun isEmpty(): Boolean {
        TODO("Not yet implemented")
    }

    override fun add(element: Quad): Boolean = this.base.add(element)

    override fun addAll(elements: Collection<Quad>): Boolean = this.base.addAll(elements)

    override fun clear() = this.base.clear()

    override fun iterator(): MutableIterator<Quad> {
        TODO("Not yet implemented")
    }

    override fun remove(element: Quad): Boolean = this.base.remove(element)

    override fun removeAll(elements: Collection<Quad>): Boolean = this.base.removeAll(elements)

    override fun retainAll(elements: Collection<Quad>): Boolean = this.base.retainAll(elements)


}