package org.megras.lang.sparql.functions

import org.apache.jena.sparql.expr.NodeValue
import org.apache.jena.sparql.function.FunctionBase1
import org.megras.util.embeddings.ClipEmbedderClient
import kotlinx.coroutines.runBlocking

class ClipTextFunction : FunctionBase1() {

    override fun exec(arg: NodeValue): NodeValue {
        val text = arg.asString()

        return runBlocking {
            val client = ClipEmbedderClient("localhost", 50051)
            try {
                val embedding: List<Float> = client.getTextEmbedding(text)
                val embeddingString = embedding.joinToString(",")
                return@runBlocking NodeValue.makeString(embeddingString)

            } catch (e: Exception) {
                println("Error getting text embedding for '$text': ${e.message}")
                return@runBlocking NodeValue.makeString("")
            } finally {
                client.close()
            }
        }
    }
}