package org.megras.graphstore

import com.google.common.collect.HashMultimap
import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.data.graph.URIValue
import org.megras.data.graph.VectorValue
import java.io.Serializable
import kotlin.math.min


class IndexedMutableQuadSet : MutableQuadSet, Serializable {

    private val quads = BasicMutableQuadSet()

    private val sIndex = HashMultimap.create<QuadValue, Quad>()
    private val pIndex = HashMultimap.create<QuadValue, Quad>()
    private val oIndex = HashMultimap.create<QuadValue, Quad>()



    override fun getId(id: Long): Quad? = quads.getId(id)

    override fun filterSubject(subject: QuadValue): QuadSet = BasicMutableQuadSet(sIndex[subject] ?: emptyList())

    override fun filterPredicate(predicate: QuadValue): QuadSet = BasicMutableQuadSet(pIndex[predicate] ?: emptyList())

    override fun filterObject(`object`: QuadValue): QuadSet = BasicMutableQuadSet(oIndex[`object`] ?: emptyList())

    override fun filter(
        subjects: Collection<QuadValue>?,
        predicates: Collection<QuadValue>?,
        objects: Collection<QuadValue>?
    ): QuadSet {

        val sCount = if (subjects?.isEmpty() == true) Int.MAX_VALUE else subjects?.size ?: Int.MAX_VALUE
        val oCount = if (objects?.isEmpty() == true) Int.MAX_VALUE else objects?.size ?: Int.MAX_VALUE

        val sSet = subjects?.toSet()
        val pSet = predicates?.toSet()
        val oSet = objects?.toSet()

        val sFilter : (Quad) -> Boolean = if (!sSet.isNullOrEmpty()) {{it.subject in sSet }} else {{true}}
        val pFilter : (Quad) -> Boolean = if (!pSet.isNullOrEmpty()) {{it.predicate in pSet }} else {{true}}
        val oFilter : (Quad) -> Boolean = if (!oSet.isNullOrEmpty()) {{it.`object` in oSet }} else {{true}}


        val minCount = min(sCount, oCount)

        if (minCount == Int.MAX_VALUE) {
            return BasicQuadSet()
        }

        val set = mutableSetOf<Quad>()

        if (subjects.isNullOrEmpty() && objects.isNullOrEmpty()) { //only filtering by predicates
            (predicates ?: emptyList()).flatMapTo(set){
                pIndex[it] ?: emptyList()
            }
            return BasicQuadSet(set)
        }

        return if (sCount < oCount) {
            (subjects ?: emptyList()).flatMapTo(set){ quads ->
                sIndex[quads].filter { oFilter(it) && pFilter(it) }
            }
            BasicQuadSet(set)
        } else {
            (objects ?: emptyList()).flatMapTo(set){ quads ->
                sIndex[quads].filter { sFilter(it) && pFilter(it) }
            }
            BasicQuadSet(set)
        }

    }

    override fun toMutable(): MutableQuadSet = this

    override fun toSet(): Set<Quad> = this

    override fun plus(other: QuadSet): QuadSet = BasicMutableQuadSet(this.quads + other)

    override fun nearestNeighbor(predicate: QuadValue, `object`: VectorValue, count: Int, distance: Distance): QuadSet = this.filterPredicate(predicate).nearestNeighbor(predicate, `object`, count, distance)

    override fun textFilter(predicate: QuadValue, objectFilterText: String): QuadSet = filterPredicate(predicate).textFilter(predicate, objectFilterText)

    override val size: Int
        get() = quads.size

    override fun contains(element: Quad): Boolean = quads.contains(element)

    override fun containsAll(elements: Collection<Quad>): Boolean = quads.containsAll(elements)

    override fun isEmpty(): Boolean = quads.isEmpty()

    override fun iterator(): MutableIterator<Quad> = quads.iterator()

    override fun add(element: Quad): Boolean {

        if(quads.add(element)) {

            sIndex.put(element.subject, element)
            pIndex.put(element.predicate, element)
            oIndex.put(element.`object`, element)

            return true
        }
        return false

    }

    override fun addAll(elements: Collection<Quad>): Boolean {
        if (quads.addAll(elements)) {
            elements.groupBy { it.subject }.forEach { (qv, q) -> if(qv is URIValue) sIndex.putAll(qv, q) }
            elements.groupBy { it.predicate }.forEach { (qv, q) -> if(qv is URIValue) pIndex.putAll(qv, q) }
            elements.groupBy { it.`object` }.forEach { (qv, q) -> if(qv is URIValue) oIndex.putAll(qv, q) }
            System.gc()
            return true
        }
        return false

    }

    override fun clear() {
        quads.clear()
        sIndex.clear()
        pIndex.clear()
        oIndex.clear()
    }

    override fun remove(element: Quad): Boolean {
        TODO("Not yet implemented")
    }

    override fun removeAll(elements: Collection<Quad>): Boolean {
        TODO("Not yet implemented")
    }

    override fun retainAll(elements: Collection<Quad>): Boolean {
        TODO("Not yet implemented")
    }
}