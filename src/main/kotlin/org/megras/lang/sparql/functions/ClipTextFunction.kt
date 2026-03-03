package org.megras.lang.sparql.functions

import com.google.common.cache.CacheBuilder
import org.apache.jena.sparql.expr.NodeValue
import org.apache.jena.sparql.function.FunctionBase1
import org.megras.util.services.ClipEmbedderClient
import kotlinx.coroutines.runBlocking

class ClipTextFunction : FunctionBase1() {

    private val vectorCache = CacheBuilder.newBuilder().maximumSize(100).build<String, NodeValue>()

    override fun exec(arg: NodeValue): NodeValue {
        val inputText = arg.asString()

        val text = if (inputText.startsWith("\"") && inputText.endsWith("\"")) {
            inputText.substring(1, inputText.length - 1)
        } else {
            inputText
        }

        val cached = vectorCache.getIfPresent(text)

        if (cached != null) {
            return cached
        }

        return runBlocking {
            val client = ClipEmbedderClient("localhost", 50051)
            try {
                val embedding: List<Float> = client.getTextEmbedding(text)
                val embeddingString = embedding.joinToString(",")
                val node = NodeValue.makeString(embeddingString)
                vectorCache.put(text, node)
                return@runBlocking node

            } catch (e: Exception) {
                println("Error getting text embedding for '$text': ${e.message}")
                return@runBlocking NodeValue.makeString("")
            } finally {
                client.close()
            }
        }
    }
}