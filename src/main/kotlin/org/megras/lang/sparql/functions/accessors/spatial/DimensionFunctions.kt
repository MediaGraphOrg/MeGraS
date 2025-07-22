package org.megras.lang.sparql.functions.accessors.spatial

import org.apache.jena.sparql.expr.NodeValue
import org.apache.jena.sparql.function.FunctionBase1
import org.megras.data.graph.URIValue
import org.megras.graphstore.MutableQuadSet
import org.megras.lang.sparql.SparqlUtil
import org.megras.segmentation.Bounds
import org.megras.segmentation.BoundsUtil

abstract class DimensionFunctionBase : FunctionBase1() {
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

        val value = extractDimension(bounds)
        return NodeValue.makeDouble(value)
    }

    protected abstract fun extractDimension(bounds: Bounds): Double
}

class WidthFunction : DimensionFunctionBase() {
    override fun extractDimension(bounds: Bounds): Double = bounds.getXDimension()
}

class HeightFunction : DimensionFunctionBase() {
    override fun extractDimension(bounds: Bounds): Double = bounds.getYDimension()
}

class DepthFunction : DimensionFunctionBase() {
    override fun extractDimension(bounds: Bounds): Double = bounds.getZDimension()
}

class DurationFunction : DimensionFunctionBase() {
    override fun extractDimension(bounds: Bounds): Double = bounds.getTDimension()
}

class XyztFunction : FunctionBase1() {
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

        val xyzt = bounds.toString()
        return NodeValue.makeString(xyzt)
    }
}