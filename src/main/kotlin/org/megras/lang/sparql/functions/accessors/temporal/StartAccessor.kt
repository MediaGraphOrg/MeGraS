package org.megras.lang.sparql.functions.accessors.temporal

import org.apache.jena.sparql.expr.NodeValue
import org.apache.jena.sparql.function.FunctionBase1
import org.megras.data.graph.TemporalValue
import org.megras.graphstore.MutableQuadSet
import org.megras.lang.sparql.SparqlUtil
import org.megras.data.graph.URIValue

class StartAccessor : FunctionBase1() {

    companion object {
        private lateinit var quadSet: MutableQuadSet

        // Common predicates for start times across different media types
        private val START_TIME_PREDICATES = setOf(
            URIValue("http://purl.org/dc/terms/created"),
            URIValue("http://www.w3.org/2006/time#hasBeginning"),
            URIValue("http://schema.org/startDate"),
            URIValue("http://lsc.dcu.ie/schema#utc_time")
            // Add more predicates as needed
        )

        fun setQuads(quadSet: MutableQuadSet) {
            this.quadSet = quadSet
        }

        private fun getStart(subject: URIValue): NodeValue {
            // Get the start time from the subject quads
            return NodeValue.makeDateTime(AccessorUtil.getDateTime(subject, this.quadSet, START_TIME_PREDICATES).toString())
        }

        internal fun getStartQV(subject: URIValue): TemporalValue? {
            return AccessorUtil.getDateTime(subject, this.quadSet, START_TIME_PREDICATES)
        }
    }

    override fun exec(arg: NodeValue): NodeValue {
        // Get the subject from the argument
        val subject = SparqlUtil.toQuadValue(arg.asNode()) as URIValue?
            ?: throw IllegalArgumentException("Invalid subject")

        return getStart(subject)
    }
}