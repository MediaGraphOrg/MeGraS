package org.megras.lang.sparql.functions

import org.apache.jena.sparql.expr.NodeValue
import org.apache.jena.sparql.function.FunctionBase3
import org.megras.util.knn.CosineDistance

class CosineSimFunction : FunctionBase3() {

    override fun exec(arg1: NodeValue, arg2: NodeValue, arg3: NodeValue): NodeValue {
        val vec1 = arg1.asNode().literal.value.toString().split(",").map { it.toFloat() }.toFloatArray()
        val vec2 = arg2.asNode().literal.value.toString().split(",").map { it.toFloat() }.toFloatArray()
        val threshold = arg3.asNode().literal.value.toString().toDouble()

        val similarity = 1.0 - CosineDistance.distance(vec1, vec2)
        return NodeValue.makeBoolean(similarity >= threshold)
    }
}