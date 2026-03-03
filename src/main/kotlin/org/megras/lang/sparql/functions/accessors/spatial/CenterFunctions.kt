package org.megras.lang.sparql.functions.accessors.spatial

import org.apache.jena.sparql.expr.NodeValue
import org.apache.jena.sparql.function.FunctionBase1
import org.megras.data.graph.URIValue
import org.megras.graphstore.MutableQuadSet
import org.megras.lang.sparql.SparqlUtil
import org.megras.segmentation.BoundsUtil

class BoundsCenterFunction : FunctionBase1() {
    companion object {
        private lateinit var quads: MutableQuadSet

        fun setQuads(quadSet: MutableQuadSet) {
            this.quads = quadSet
        }
    }

    override fun exec(arg: NodeValue): NodeValue {
        val subject = SparqlUtil.toQuadValue(arg.asNode()) as URIValue?
            ?: throw IllegalArgumentException("Invalid subject. Expected a URI.")

        val bounds = BoundsUtil.getBounds(subject, quads)
            ?: throw IllegalArgumentException("Invalid subject. No bounds found.")
        val center = bounds.getCenter()
        val centerString = center.map {
            if (it.isNaN()) {
                "-"
            } else {
                it
            }
        }.joinToString(",")

        return NodeValue.makeString(centerString)
    }
}

class SegmentCenterFunction : FunctionBase1() {
    companion object {
        private lateinit var quads: MutableQuadSet

        fun setQuads(quadSet: MutableQuadSet) {
            this.quads = quadSet
        }
    }

    override fun exec(arg: NodeValue): NodeValue {
        val subject = SparqlUtil.toQuadValue(arg.asNode()) as URIValue?
            ?: throw IllegalArgumentException("Invalid subject. Expected a URI.")

        val bounds = BoundsUtil.getSegmentBounds(subject, quads)
            ?: throw IllegalArgumentException("Invalid subject. No bounds found.")
        val center = bounds.getCenter()
        val centerString = center.map {
            if (it.isNaN()) {
                "-"
            } else {
                it
            }
        }.joinToString(",")

        return NodeValue.makeString(centerString)
    }
}