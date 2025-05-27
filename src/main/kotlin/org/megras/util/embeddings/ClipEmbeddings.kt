package org.megras.util.embeddings


object ClipEmbeddings {
    fun getTextEmbedding(text: String): FloatArray {
        try {
            return FloatArray(0)
        } catch (e: Exception) {
            println("Error: ${e.message}")
            return FloatArray(0)
        }
    }

    fun getImageEmbedding(imagePath: String): FloatArray {
        try {
            return FloatArray(0)
        } catch (e: Exception) {
            println("Error: ${e.message}")
            return FloatArray(0)
        }
    }
}
