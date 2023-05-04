package org.megras.graphstore

import org.megras.data.graph.*
import org.megras.data.schema.MeGraS
import org.megras.util.knn.DistancePairComparator
import org.megras.util.knn.FixedSizePriorityQueue

open class BasicQuadSet(private val quads: Set<Quad>) : QuadSet, Set<Quad> by quads {

    constructor() : this(setOf())

    override fun getId(id: Long): Quad? = quads.find { it.id == id }

    override fun filterSubject(subject: QuadValue): QuadSet = BasicQuadSet(quads.filter { it.subject == subject }.toSet())

    override fun filterPredicate(predicate: QuadValue): QuadSet = BasicQuadSet(quads.filter { it.predicate == predicate }.toSet())

    override fun filterObject(`object`: QuadValue): QuadSet = BasicQuadSet(quads.filter { it.`object` == `object` }.toSet())

    override fun filter(
        subjects: Collection<QuadValue>?,
        predicates: Collection<QuadValue>?,
        objects: Collection<QuadValue>?
    ): QuadSet {
        //if all attributes are unfiltered, do not filter
        if (subjects == null && predicates == null && objects == null) {
            return this
        }

        //if one attribute has no matches, return empty set
        if (subjects?.isEmpty() == true || predicates?.isEmpty() == true || objects?.isEmpty() == true) {
            return BasicQuadSet(setOf())
        }

        return BasicQuadSet(quads.filter {
            subjects?.contains(it.subject) ?: true &&
                    predicates?.contains(it.predicate) ?: true &&
                    objects?.contains(it.`object`) ?: true

        }.toSet())
    }

    override fun toMutable(): MutableQuadSet = BasicMutableQuadSet(quads.toMutableSet())

    override fun toSet(): Set<Quad> = quads

    override fun plus(other: QuadSet): QuadSet = BasicQuadSet(quads + other.toSet())
    override fun nearestNeighbor(predicate: QuadValue, `object`: VectorValue, count: Int, distance: Distance): QuadSet {

        val quads = this.filter{ it.predicate == predicate && it.`object` is VectorValue && it.`object`.length == `object`.length && it.`object`.type == `object`.type }
        val vectors = quads.mapNotNull { it.`object` as? VectorValue }.toSet()

        val queue = FixedSizePriorityQueue(count, DistancePairComparator<Pair<Double, VectorValue>>())
        val dist = distance.distance()

        if (`object` is DoubleVectorValue) {
            val v = `object`.vector
            vectors.forEach {
                val vv = (it as DoubleVectorValue).vector
                val d = dist.distance(v, vv)
                queue.add(d to it)
            }
        } else {
            `object` as LongVectorValue
            val v = `object`.vector
            vectors.forEach {
                val vv = (it as LongVectorValue).vector
                val d = dist.distance(v, vv)
                queue.add(d to it)
            }
        }

        val relevantVectors = mutableSetOf<VectorValue>()
        val ret = BasicMutableQuadSet()
        queue.forEach {
            relevantVectors.add(it.second)
            ret.add(Quad(it.second, MeGraS.QUERY_DISTANCE.uri, DoubleValue(it.first)))
        }
        quads.forEach {
            if (it.`object` in relevantVectors) {
                ret.add(it)
            }
        }

        return ret
    }

    override fun textFilter(predicate: QuadValue, objectFilterText: String): QuadSet = BasicQuadSet(quads.filter { it.predicate == predicate && it.`object` is StringValue && it.`object`.value.contains(objectFilterText) }.toSet())


}