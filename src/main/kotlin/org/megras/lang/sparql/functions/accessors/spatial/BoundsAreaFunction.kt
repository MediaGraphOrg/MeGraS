package org.megras.lang.sparql.functions.accessors.spatial

import org.apache.jena.sparql.expr.NodeValue
import org.apache.jena.sparql.function.FunctionBase1
import org.megras.data.graph.URIValue
import org.megras.data.schema.MeGraS
import org.megras.graphstore.MutableQuadSet
import org.megras.lang.sparql.SparqlUtil
import org.megras.segmentation.Bounds

class BoundsAreaFunction : FunctionBase1() {
    companion object {
        private lateinit var quads: MutableQuadSet

        fun setQuads(quadSet: MutableQuadSet) {
            this.quads = quadSet
        }
    }

    override fun exec(arg: NodeValue): NodeValue {
        val subject = SparqlUtil.toQuadValue(arg.asNode()) as URIValue?
            ?: throw IllegalArgumentException("Invalid subject. Expected a URI.")

        val boundsString = (quads.filter(setOf(subject), setOf(MeGraS.BOUNDS.uri), null).firstOrNull()
            ?: throw IllegalArgumentException("Invalid subject. No segmentation found."))
            .`object`.toString().replace("^^String", "")

        val bounds = Bounds(boundsString)
        if (bounds.dimensions < 2) {
            throw IllegalArgumentException("At least two dimensions are required.")
        } else if (bounds.dimensions > 3) {
            throw IllegalArgumentException("Too many dimensions.")
        }
        val area = when {
            bounds.hasX() && bounds.hasY() -> bounds.getXYArea()
            bounds.hasY() && bounds.hasZ() -> bounds.getYZArea()
            bounds.hasX() && bounds.hasZ() -> bounds.getXZArea()
            else -> throw IllegalArgumentException("Invalid dimensions.")
        }

        return NodeValue.makeDouble(area)
    }
}