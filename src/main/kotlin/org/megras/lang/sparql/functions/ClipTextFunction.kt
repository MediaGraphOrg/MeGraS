package org.megras.lang.sparql.functions

import org.apache.jena.sparql.expr.NodeValue
import org.apache.jena.sparql.function.FunctionBase1
import org.megras.util.djl.ClipEmbeddings

class ClipTextFunction : FunctionBase1() {

    override fun exec(arg: NodeValue): NodeValue {
        val text = arg.asString()

        val embeddingResult = ClipEmbeddings.getTextEmbedding(text)
        //TODO : Convert the FloatArray to a format suitable for NodeValue
        val embeddingString = embeddingResult.joinToString(",")
        return NodeValue.makeString(embeddingString)
    }
}