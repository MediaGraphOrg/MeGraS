package org.megras.util.djl

import ai.djl.inference.Predictor
import ai.djl.modality.cv.Image
import ai.djl.modality.cv.ImageFactory
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
        
        fun getImageEmbedding(imagePath: String): FloatArray {
            val criteria: Criteria<Image, FloatArray> = Criteria.builder()
                .setTypes(Image::class.java, FloatArray::class.java)
                .optModelUrls("djl://ai.djl.huggingface.pytorch/sentence-transformers/clip-ViT-B-32-multilingual-v1")
                .optEngine("PyTorch")
                .build()

            try {
                val image: Image = ImageFactory.getInstance().fromFile(java.nio.file.Paths.get(imagePath))
                criteria.loadModel().use { model: ZooModel<Image, FloatArray> ->
                    model.newPredictor().use { predictor: Predictor<Image, FloatArray> ->
                        val embeddings: FloatArray = predictor.predict(image)
                        return embeddings
                    }
                }
            } catch (e: Exception) {
                throw Exception("Error calculating image embedding: ${e.message}")
            }
        }
    }
}
