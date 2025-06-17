package org.megras.graphstore.derived

import org.megras.data.graph.*
import org.megras.data.schema.MeGraS
import org.megras.graphstore.*
import org.megras.util.knn.DistancePairComparator
import org.megras.util.knn.FixedSizePriorityQueue

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

        val derived = relevantHandlers.flatMap { handler ->
            //check if already present
            if (existing.filterPredicate(handler.predicate)
                    .isNotEmpty()
            ) { //TODO a hasPredicate method would be more efficient
                return@flatMap emptyList()
            }
            handler.derive(subject).map { obj -> Quad(subject, handler.predicate, obj) }
        }

        //store derived values for future use
        if (derived.isNotEmpty()) {
            base.addAll(derived)
            return existing + BasicQuadSet(derived.toSet())
        }

        return existing
    }

    private fun getAllBySubject(): Map<URIValue, Collection<Quad>> =
        //FIXME this is not efficient, but it works for now
        this.base.iterator().asSequence().filter { it.subject is URIValue }.groupBy { it.subject as URIValue }


    override fun filterPredicate(predicate: QuadValue): QuadSet {

        val existing = this.base.filterPredicate(predicate)

        val handler = this.handlers[predicate]

        if (handler == null) { //nothing to derive
            return existing
        }

        val subjects = getAllBySubject()

        val derived = subjects.flatMap { (subject, quads) ->
            if (!handler.canDerive(subject)) {
                return@flatMap emptyList()
            }
            if (quads.any { it.predicate == predicate }) { //already exists
                return@flatMap emptyList()
            }
            handler.derive(subject).map { obj -> Quad(subject, handler.predicate, obj) }
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

        val subs = (subjects ?: getAllBySubject().keys).filterIsInstance<URIValue>()
        val derived = subs.chunked(100).flatMap { chunk ->
            val existingQuads = this.base.filter(
                chunk,
                relevantHandlers.map { it.predicate },
                null
            )
            val quadsBySubject = existingQuads.groupBy { it.subject }

            chunk.flatMap { subject ->
                val presentPredicates = quadsBySubject[subject]?.map { it.predicate }?.toSet() ?: emptySet()

                relevantHandlers.flatMap { handler ->
                    if (handler.predicate in presentPredicates) {
                        return@flatMap emptyList()
                    }
                    if (!handler.canDerive(subject)) {
                        return@flatMap emptyList()
                    }
                    handler.derive(subject).map { obj -> Quad(subject, handler.predicate, obj) }
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

        val existing = this.base.nearestNeighbor(predicate, `object`, count, distance, invert)

        val handler = this.handlers[predicate] ?: return existing

        val subjects = getAllBySubject()

        val derived = subjects.flatMap { (subject, quads) ->
            if (!handler.canDerive(subject)) {
                return@flatMap emptyList()
            }
            if (quads.any { it.predicate == predicate }) { //already exists
                return@flatMap emptyList()
            }
            handler.derive(subject).map { obj -> Quad(subject, handler.predicate, obj) }
        }

        if (derived.isNotEmpty()) {
            base.addAll(derived)
        }

        val queue = FixedSizePriorityQueue(count, DistancePairComparator<Pair<Double, VectorValue>>())
        val order = if (invert) -1 else 1
        val dist = distance.distance()

        val vectors = existing.mapNotNull { it.`object` as? VectorValue }.toSet()
        val derivedVectors = derived.mapNotNull { it.`object` as? VectorValue }.toSet()

        val candidates = vectors.asSequence() + derivedVectors.asSequence()

        when(`object`) {
            is DoubleVectorValue -> {
                val v = `object`.vector
                candidates.forEach {
                    val vv = (it as DoubleVectorValue).vector
                    val d = order * dist.distance(v, vv)
                    queue.add(d to it)
                }
            }
            is LongVectorValue -> {
                val v = `object`.vector
                candidates.forEach {
                    val vv = (it as LongVectorValue).vector
                    val d = order * dist.distance(v, vv)
                    queue.add(d to it)
                }
            }
            is FloatVectorValue -> {
                val v = `object`.vector
                candidates.forEach {
                    val vv = (it as FloatVectorValue).vector
                    val d = order * dist.distance(v, vv)
                    queue.add(d to it)
                }
            }
        }

        val relevantVectors = mutableSetOf<VectorValue>()
        val ret = BasicMutableQuadSet()
        queue.forEach {
            relevantVectors.add(it.second)
            ret.add(Quad(it.second, MeGraS.QUERY_DISTANCE.uri, DoubleValue(it.first)))
        }
        existing.forEach {
            if (it.`object` in relevantVectors) {
                ret.add(it)
            }
        }

        return ret
    }

    override fun textFilter(
        predicate: QuadValue,
        objectFilterText: String
    ): QuadSet {

        val existing = this.base.textFilter(predicate, objectFilterText)

        val handler = this.handlers[predicate] ?: return existing

        val subjects = getAllBySubject()

        val derived = subjects.flatMap { (subject, quads) ->
            if (!handler.canDerive(subject)) {
                return@flatMap emptyList()
            }
            if (quads.any { it.predicate == predicate }) { //already exists
                return@flatMap emptyList()
            }
            handler.derive(subject).map { obj ->
                Quad(subject, handler.predicate, obj)
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
        val objs = handler.derive(element.subject)
        if (objs.isEmpty()) {
            return false
        }

        this.base.addAll(
            objs.map {
                Quad(element.subject, element.predicate, it)
            }
        )

        return objs.contains(element.`object`)
    }

    override fun containsAll(elements: Collection<Quad>): Boolean = elements.all { contains(it) }

    override fun isEmpty(): Boolean {
        if (!this.base.isEmpty()) {
            return false
        }
        if (this.handlers.isEmpty()) {
            return true
        }
        //TODO check if there are any candidates to derive relations from
        return false
    }

    override fun add(element: Quad): Boolean = this.base.add(element)

    override fun addAll(elements: Collection<Quad>): Boolean = this.base.addAll(elements)

    override fun clear() = this.base.clear()

    override fun iterator(): MutableIterator<Quad> = this.base.iterator()

    override fun remove(element: Quad): Boolean = this.base.remove(element)

    override fun removeAll(elements: Collection<Quad>): Boolean = this.base.removeAll(elements)

    override fun retainAll(elements: Collection<Quad>): Boolean = this.base.retainAll(elements)


}