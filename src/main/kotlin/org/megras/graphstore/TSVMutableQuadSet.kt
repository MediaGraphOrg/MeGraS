package org.megras.graphstore

import com.github.doyaaaaaken.kotlincsv.dsl.csvReader
import com.github.doyaaaaaken.kotlincsv.dsl.csvWriter
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.data.graph.VectorValue
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class TSVMutableQuadSet(private val tsvFileName : String, private val useCompression: Boolean = false) : MutableQuadSet, PersistableQuadSet {

    private val cache = IndexedMutableQuadSet()
    private var lastStoreTime = 0L

    private val tsvReader = csvReader {
        delimiter = '\t'
        skipEmptyLine = true
    }

    private val tsvWriter = csvWriter {
        delimiter = '\t'
    }

    init {
        load()
    }

    override fun load() {
        print("loading quads from tsv...")
        cache.clear()

        val tsvFile = File(tsvFileName)

        if (!tsvFile.exists() || tsvFile.length() == 0L){
            return
        }

        val inputStream = if (useCompression) {
            BZip2CompressorInputStream(FileInputStream(tsvFile), true)
        } else {
            FileInputStream(tsvFile)
        }

        var i = 0

        tsvReader.open(inputStream) {

            val buffer = ArrayList<Quad>(1000)

            readAllWithHeaderAsSequence().forEach {
                buffer.add(
                    Quad(
                        QuadValue.of(it["subject"]!!),
                        QuadValue.of(it["predicate"]!!),
                        QuadValue.of(it["object"]!!)
                    )
                )
                if (buffer.size >= 1000) {
                    cache.addAll(buffer)
                    buffer.clear()
                }
                if (++i % 1_000_000 == 0) {
                    print('.')
                }
            }
            cache.addAll(buffer)
        }

        print("done")
        lastStoreTime = System.currentTimeMillis()

    }



    @Synchronized
    private fun hintStore(force: Boolean = false) {

        //rate limit writes
        if (!force && (System.currentTimeMillis() - lastStoreTime) < 600_000) {
            return
        }

        print("writing quads to tsv...")

        val tmpFile = File("$tsvFileName.tmp")

        val outputStream = if(useCompression) {
            BZip2CompressorOutputStream(FileOutputStream(tmpFile), 3)
        } else {
            FileOutputStream(tmpFile)
        }

        var i = 0
        tsvWriter.open(outputStream) {
            writeRow("subject", "predicate", "object")
            cache.forEach {
                writeRow(it.subject, it.predicate, it.`object`)
                if (++i % 1000000 == 0) {
                    print('.')
                }
            }
        }

        val tsvFile = File(tsvFileName)
        if (tsvFile.exists()) {
            tsvFile.delete()
        }
        tmpFile.renameTo(tsvFile)

        lastStoreTime = System.currentTimeMillis()
        println("done")

    }

    override fun store() {
        hintStore(true)
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

    override fun textFilter(predicate: QuadValue, objectFilterText: String): QuadSet = this.cache.textFilter(predicate, objectFilterText)

    override val size: Int
        get() = cache.size

    override fun contains(element: Quad): Boolean = cache.contains(element)

    override fun containsAll(elements: Collection<Quad>): Boolean = cache.containsAll(elements)

    override fun isEmpty(): Boolean = cache.isEmpty()

    override fun iterator(): MutableIterator<Quad> = cache.iterator()

    override fun add(element: Quad): Boolean {
        val result = cache.add(element)

        if (result) {
            hintStore()
        }

        return result

    }

    override fun addAll(elements: Collection<Quad>): Boolean {
        val result = cache.addAll(elements)

        if (result) {
            hintStore()
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
            hintStore()
        }

        return result
    }

    override fun removeAll(elements: Collection<Quad>): Boolean {
        val result = cache.removeAll(elements.toSet())

        if (result) {
            hintStore()
        }

        return result
    }

    override fun retainAll(elements: Collection<Quad>): Boolean {
        val result = cache.retainAll(elements.toSet())

        if (result) {
            hintStore()
        }

        return result
    }
}