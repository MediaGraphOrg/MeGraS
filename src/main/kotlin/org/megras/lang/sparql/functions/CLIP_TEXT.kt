package org.megras.lang.sparql.functions

import org.apache.jena.sparql.expr.NodeValue
import org.apache.jena.sparql.function.FunctionBase1
import ai.djl.repository.zoo.Criteria
import ai.djl.repository.zoo.ZooModel
import ai.djl.inference.Predictor
import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory

class CLIP_TEXT : FunctionBase1() {

    private fun calculateTextEmbedding(text: String): FloatArray {
        val criteria: Criteria<String, FloatArray> = Criteria.builder()
            .setTypes(String::class.java, FloatArray::class.java)
            .optModelUrls("djl://ai.djl.huggingface.pytorch/sentence-transformers/clip-ViT-B-32-multilingual-v1")
            .optTranslatorFactory(TextEmbeddingTranslatorFactory())
            .optEngine("PyTorch")
            .build()

        try {
            criteria.loadModel().use { model: ZooModel<String, FloatArray> ->
                model.newPredictor().use { predictor: Predictor<String, FloatArray> ->
                    val embeddings: FloatArray = predictor.predict(text)
                    return embeddings
                }
            }
        } catch (e: Exception) {
            throw Exception("Error calculating text embedding: ${e.message}")
        }
    }

    override fun exec(arg: NodeValue): NodeValue {
        val text = arg.asString()

        val embeddingResult = calculateTextEmbedding(text)
        //TODO : Convert the FloatArray to a format suitable for NodeValue
        val embeddingString = embeddingResult.joinToString(",")
        return NodeValue.makeString(embeddingString)
    }
}