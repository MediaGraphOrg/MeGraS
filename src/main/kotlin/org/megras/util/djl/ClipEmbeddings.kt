package org.megras.util.djl

import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory
import ai.djl.inference.Predictor
import ai.djl.repository.zoo.Criteria
import ai.djl.repository.zoo.ZooModel
import kotlin.FloatArray
import kotlin.String
import kotlin.use

class ClipEmbeddings {
    companion object {
        fun getTextEmbedding(text: String): FloatArray {
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
    }
}