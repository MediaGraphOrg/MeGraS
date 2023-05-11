package org.megras.graphstore

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.data.graph.VectorValue
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class BinarySerializedMutableQuadSet private constructor(
    private var quads: MutableQuadSet,
    private val fileName: String,
    private val useCompression: Boolean
): MutableQuadSet, PersistableQuadSet {

    constructor(fileName : String, useCompression: Boolean = false): this(IndexedMutableQuadSet(), fileName, useCompression) {
        load()
    }



    override fun store() {

        print("writing quadset...")

        val tmpFile = File("$fileName.tmp")

        val outputStream = if(useCompression) {
            BZip2CompressorOutputStream(FileOutputStream(tmpFile), 1)
        } else {
            FileOutputStream(tmpFile)
        }

        val objectObjectInputStream = ObjectOutputStream(outputStream)
        objectObjectInputStream.writeObject(quads)
        objectObjectInputStream.flush()
        objectObjectInputStream.close()

        val file = File(fileName)
        if (file.exists()) {
            file.delete()
        }
        tmpFile.renameTo(file)

        println("done")

    }

    override fun load() {

        print("reading quadset...")

        val file = File(fileName)

        if (!file.exists() || file.length() == 0L){
            return
        }

        val inputStream = if (useCompression) {
            BZip2CompressorInputStream(FileInputStream(file), true)
        } else {
            FileInputStream(fileName)
        }

        val objectStream = ObjectInputStream(inputStream)
        quads = objectStream.readObject() as IndexedMutableQuadSet

        objectStream.close()

        println("done")
    }

    override fun getId(id: Long): Quad? = quads.getId(id)

    override fun filterSubject(subject: QuadValue): QuadSet = quads.filterSubject(subject)

    override fun filterPredicate(predicate: QuadValue): QuadSet = quads.filterPredicate(predicate)

    override fun filterObject(`object`: QuadValue): QuadSet = quads.filterObject(`object`)

    override fun filter(
        subjects: Collection<QuadValue>?,
        predicates: Collection<QuadValue>?,
        objects: Collection<QuadValue>?
    ): QuadSet = quads.filter(subjects, predicates, objects)

    override fun toMutable(): MutableQuadSet = this

    override fun toSet(): Set<Quad> = this

    override fun plus(other: QuadSet): QuadSet = quads.plus(other)

    override fun nearestNeighbor(predicate: QuadValue, `object`: VectorValue, count: Int, distance: Distance): QuadSet = quads.nearestNeighbor(predicate, `object`, count, distance)

    override fun textFilter(predicate: QuadValue, objectFilterText: String): QuadSet = quads.textFilter(predicate, objectFilterText)

    override val size: Int
        get() = quads.size

    override fun contains(element: Quad): Boolean = quads.contains(element)

    override fun containsAll(elements: Collection<Quad>): Boolean = quads.containsAll(elements)

    override fun isEmpty(): Boolean = quads.isEmpty()

    override fun iterator(): MutableIterator<Quad> = quads.iterator()

    override fun add(element: Quad): Boolean = quads.add(element)

    override fun addAll(elements: Collection<Quad>): Boolean = quads.addAll(elements)

    override fun clear() = quads.clear()

    override fun remove(element: Quad): Boolean = quads.remove(element)

    override fun removeAll(elements: Collection<Quad>): Boolean = quads.removeAll(elements.toSet())

    override fun retainAll(elements: Collection<Quad>): Boolean = quads.retainAll(elements.toSet())
}