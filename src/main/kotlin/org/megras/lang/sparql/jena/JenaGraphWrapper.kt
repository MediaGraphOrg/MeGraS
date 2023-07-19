package org.megras.lang.sparql.jena

import org.apache.jena.graph.Node
import org.apache.jena.graph.Triple
import org.apache.jena.graph.impl.GraphBase
import org.apache.jena.util.iterator.ExtendedIterator
import org.megras.data.graph.QuadValue
import org.megras.data.graph.URIValue
import org.megras.graphstore.QuadSet
import org.megras.lang.sparql.SparqlUtil
import org.megras.lang.sparql.SparqlUtil.toQuadValue

class JenaGraphWrapper(private val quads: QuadSet) : GraphBase() {


    override fun graphBaseFind(triplePattern: Triple): ExtendedIterator<Triple> {

        val s = toQuadValue(triplePattern.subject)
        val p = toQuadValue(triplePattern.predicate)
        val o = toQuadValue(triplePattern.`object`)

        val quadset = this.quads.filter(
            if (s != null) {
                listOf(s)
            } else null,
            if (p != null) {
                listOf(p)
            } else null,
            if (o != null) {
                listOf(o)
            } else null,
        )

        return QuadSetIterator(quadset)

    }

}