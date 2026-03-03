package org.megras.graphstore.implicit

import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.data.graph.URIValue
import org.megras.data.graph.VectorValue
import org.megras.graphstore.BasicQuadSet
import org.megras.graphstore.Distance
import org.megras.graphstore.MutableQuadSet
import org.megras.graphstore.QuadSet

class ImplicitRelationMutableQuadSet(
    private val base: MutableQuadSet,
    handlerList: Collection<ImplicitRelationHandler>,
    regexHandlerList: Collection<RegexImplicitRelationHandler> = emptyList()
) : MutableQuadSet {

    private val handlers = handlerList.associateBy { it.predicate }
    private val regexHandlers = regexHandlerList.toList()
    private val dynamicHandlerCache = mutableMapOf<String, ImplicitRelationHandler>()

    init {
        handlers.values.forEach { it.init(this) }
        regexHandlers.forEach { handler ->
            // If the regex handler needs to be initialized with the quad set
            try {
                handler.javaClass.getMethod("init", ImplicitRelationMutableQuadSet::class.java)
                    .invoke(handler, this)
            } catch (_: NoSuchMethodException) {}
        }
    }

    private fun findHandler(predicate: QuadValue): ImplicitRelationHandler? {
        if (predicate !is URIValue) return null
        // Exact match
        handlers[predicate]?.let { return it }
        // Regex match
        for (regexHandler in regexHandlers) {
            val params = regexHandler.matchPredicate(predicate)
            if (params != null) {
                val cacheKey = predicate.value
                return dynamicHandlerCache.getOrPut(cacheKey) {
                    val handler = regexHandler.getHandler(params)
                    handler.init(this)
                    handler
                }
            }
        }
        return null
    }

    override fun getId(id: Long): Quad? = this.base.getId(id)

    override fun filterSubject(subject: QuadValue): QuadSet {
        // Skip implicit relations - this is a wildcard predicate query
        // Implicit relations should only be computed when explicitly requested
        return this.base.filterSubject(subject)
    }

    override fun filterPredicate(predicate: QuadValue): QuadSet {
        val handler = findHandler(predicate)
        return handler?.findAll() ?: this.base.filterPredicate(predicate)
    }

    override fun filterObject(`object`: QuadValue): QuadSet {
        // Skip implicit relations - this is a wildcard predicate query
        // Implicit relations should only be computed when explicitly requested
        return this.base.filterObject(`object`)
    }

    override fun filter(
        subjects: Collection<QuadValue>?,
        predicates: Collection<QuadValue>?,
        objects: Collection<QuadValue>?
    ): QuadSet {
        val existing = this.base.filter(subjects, predicates, objects)

        // Skip implicit relations when predicate is not specified (wildcard query)
        // Implicit relations should only be computed when explicitly requested
        if (predicates == null) {
            return existing
        }

        val relevantHandlers = predicates.mapNotNull { findHandler(it) }

        if (relevantHandlers.isEmpty()) {
            return existing
        }

        val implicit = relevantHandlers.flatMap { handler ->
            when {
                (subjects.isNullOrEmpty() && objects.isNullOrEmpty()) -> {
                    handler.findAll()
                }
                !subjects.isNullOrEmpty() -> {
                    subjects.filterIsInstance<URIValue>().flatMap { subject ->
                        handler.findObjects(subject).map { `object` -> Quad(subject, handler.predicate, `object`) }
                    }
                }
                !objects.isNullOrEmpty() -> {
                    objects.filterIsInstance<URIValue>().flatMap { `object` ->
                        handler.findSubjects(`object`).map { subject -> Quad(subject, handler.predicate, `object`) }
                    }
                }
                else -> emptyList()
            }
        }

        return existing + BasicQuadSet(implicit.toSet())
    }

    override fun toMutable(): MutableQuadSet = this

    override fun toSet(): Set<Quad> = this.base.toSet()

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
        // Implicit relations do not need to handle nearest neighbors for predicates (since they are document relations)
        return this.base.nearestNeighbor(predicate, `object`, count, distance, invert)
    }

    override fun textFilter(
        predicate: QuadValue,
        objectFilterText: String
    ): QuadSet {
        // Check if this is an implicit relation predicate
        val handler = findHandler(predicate)

        if (handler != null) {
            // For implicit relations, we need to compute all quads and filter in-memory
            // since implicit relations are computed on-the-fly
            val allQuads = handler.findAll()
            val filtered = allQuads.filter { quad ->
                val objValue = quad.`object`
                objValue is org.megras.data.graph.StringValue &&
                    objValue.value.contains(objectFilterText, ignoreCase = true)
            }
            return BasicQuadSet(filtered.toSet())
        }

        // Delegate to base for non-implicit predicates
        return this.base.textFilter(predicate, objectFilterText)
    }

    override val size: Int
            = this.base.size // technically not correct, but actual size is unknown

    override fun contains(element: Quad): Boolean {
        TODO("Not yet implemented")
    }

    override fun containsAll(elements: Collection<Quad>): Boolean = elements.all { this.contains(it) }

    override fun isEmpty(): Boolean {
        if (!this.base.isEmpty()) {
            return false
        }
        if (this.handlers.isEmpty()) {
            return true
        }
        //TODO check if there are any candidates to imply relations from
        return false
    }

    override fun add(element: Quad): Boolean {
        if (handlers.containsKey(element.predicate)) {
            return false
        }
        return this.base.add(element)
    }

    override fun addAll(elements: Collection<Quad>): Boolean = this.base.addAll(elements.filter{!handlers.containsKey(it.predicate)})

    override fun clear() = this.base.clear()

    override fun iterator(): MutableIterator<Quad> = this.base.iterator()

    override fun remove(element: Quad): Boolean = this.base.remove(element)

    override fun removeAll(elements: Collection<Quad>): Boolean = this.base.removeAll(elements)

    override fun retainAll(elements: Collection<Quad>): Boolean = this.base.retainAll(elements)

    override fun distinctObjects(predicate: QuadValue): Set<QuadValue> {
        val handler = findHandler(predicate)
        return if (handler != null) {
            // For implicit relations, we need to compute all objects
            handler.findAll().map { it.`object` }.toSet()
        } else {
            // Delegate to base for non-implicit predicates
            this.base.distinctObjects(predicate)
        }
    }

    override fun distinctSubjects(predicate: QuadValue): Set<QuadValue> {
        val handler = findHandler(predicate)
        return if (handler != null) {
            // For implicit relations, we need to compute all subjects
            handler.findAll().map { it.subject }.toSet()
        } else {
            // Delegate to base for non-implicit predicates
            this.base.distinctSubjects(predicate)
        }
    }
}