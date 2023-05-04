package org.megras.graphstore

import com.github.doyaaaaaken.kotlincsv.dsl.csvReader
import com.github.doyaaaaaken.kotlincsv.dsl.csvWriter
import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.data.graph.VectorValue
import java.io.File

class TSVMutableQuadSet(private val tsvFile : File) : MutableQuadSet {

    private val cache = BasicMutableQuadSet()

    private val tsvReader = csvReader {
        delimiter = '\t'
        escapeChar = '\\'
        skipEmptyLine = true
    }

    private val tsvWriter = csvWriter {
        delimiter = '\t'
    }

    init {
        load()
    }

    private fun load() {
        cache.toSet().clear()

        if (!tsvFile.exists() || tsvFile.length() == 0L){
            return
        }

        tsvReader.readAllWithHeader(tsvFile).map {
            cache.add(
                Quad(
                    QuadValue.of(it["subject"]!!),
                    QuadValue.of(it["predicate"]!!),
                    QuadValue.of(it["object"]!!)
                )
            )
        }

    }

    private var lastStoreTime = 0L

    @Synchronized
    fun store(force: Boolean = false) {

        //rate limit writes
        if (!force && (System.currentTimeMillis() - lastStoreTime) < 60_000) {
            return
        }

        tsvWriter.open(tsvFile) {
            writeRow("subject", "predicate", "object")
            cache.forEach {
                writeRow(it.subject, it.predicate, it.`object`)
            }
        }

        lastStoreTime = System.currentTimeMillis()

    }

    override fun getId(id: Long): Quad? = cache.getId(id)

    override fun filterSubject(subject: QuadValue): QuadSet = cache.filterSubject(subject)

    override fun filterPredicate(predicate: QuadValue): QuadSet = cache.filterPredicate(predicate)

    override fun filterObject(`object`: QuadValue): QuadSet = cache.filterObject(`object`)

    override fun filter(
        subjects: Collection<QuadValue>?,
        predicates: Collection<QuadValue>?,
        objects: Collection<QuadValue>?
    ): QuadSet = cache.filter(
        subjects, predicates, objects
    )

    override fun toMutable(): MutableQuadSet = this

    override fun toSet(): Set<Quad> = cache.toSet()

    override fun plus(other: QuadSet): QuadSet = cache.plus(other)
    override fun nearestNeighbor(predicate: QuadValue, `object`: VectorValue, count: Int, distance: Distance): QuadSet = this.cache.nearestNeighbor(predicate, `object`, count, distance)

    override fun textFilter(predicate: QuadValue, filterText: String): QuadSet = this.cache.textFilter(predicate, filterText)

    override val size: Int
        get() = cache.size

    override fun contains(element: Quad): Boolean = cache.contains(element)

    override fun containsAll(elements: Collection<Quad>): Boolean = cache.containsAll(elements)

    override fun isEmpty(): Boolean = cache.isEmpty()

    override fun iterator(): MutableIterator<Quad> = cache.iterator()

    override fun add(element: Quad): Boolean {
        val result = cache.add(element)

        if (result) {
            store()
        }

        return result

    }

    override fun addAll(elements: Collection<Quad>): Boolean {
        val result = cache.addAll(elements)

        if (result) {
            store()
        }

        return result
    }

    override fun clear() {
        cache.clear()
        store()
    }

    override fun remove(element: Quad): Boolean {
        val result = cache.remove(element)

        if (result) {
            store()
        }

        return result
    }

    override fun removeAll(elements: Collection<Quad>): Boolean {
        val result = cache.removeAll(elements.toSet())

        if (result) {
            store()
        }

        return result
    }

    override fun retainAll(elements: Collection<Quad>): Boolean {
        val result = cache.retainAll(elements.toSet())

        if (result) {
            store()
        }

        return result
    }
}