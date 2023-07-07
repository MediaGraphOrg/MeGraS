package org.megras.lang.sparql.jena

import org.apache.jena.graph.Triple
import org.apache.jena.util.iterator.NiceIterator
import org.megras.graphstore.QuadSet
import org.megras.lang.sparql.SparqlUtil.toTriple


class QuadSetIterator(private val quads: QuadSet) : NiceIterator<Triple>() {

    private val iter = this.quads.toSet().iterator()

    override fun hasNext(): Boolean = iter.hasNext()

    override fun next(): Triple = toTriple(iter.next())

    override fun close() {
        /* nop */
    }

    override fun removeNext(): Triple {
        TODO("Not yet implemented")
    }

}