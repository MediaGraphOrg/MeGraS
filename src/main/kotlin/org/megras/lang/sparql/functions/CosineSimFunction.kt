package org.megras.lang.sparql.functions

import org.apache.jena.sparql.expr.NodeValue
import org.apache.jena.sparql.function.FunctionBase2
import org.megras.util.knn.CosineDistance

class CosineSimFunction : FunctionBase2() {

    fun parseVector(vectorStr: String): FloatArray {
        /*
         * Parse a string representation of a vector into a list of floats.
         * Removes brackets and any suffix after the closing bracket.
         *
         * Example:
         * "[1.0, 2.0, 3.0]^^FloatVector" -> listOf(1.0f, 2.0f, 3.0f)
         */
        val cleanStr = vectorStr.substringBefore("]").removePrefix("[").trim()
        return cleanStr.split(",").map { it.toFloat() }.toFloatArray()
    }

    override fun exec(arg1: NodeValue, arg2: NodeValue): NodeValue {
        // Parse the string representation of the vectors into arrays of floats
        val vec1 = parseVector(arg1.asNode().literal.value.toString())
        val vec2 = parseVector(arg2.asNode().literal.value.toString())

        // Calculate cosine similarity
        val similarity = 1.0 - CosineDistance.distance(vec1, vec2)
        return NodeValue.makeDouble(similarity)
    }
}
