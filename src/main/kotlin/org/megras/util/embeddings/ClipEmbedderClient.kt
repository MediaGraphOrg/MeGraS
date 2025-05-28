package org.megras.util.embeddings

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import org.megras.util.embeddings.ClipServiceGrpcKt.ClipServiceCoroutineStub
import org.megras.util.embeddings.ClipServiceOuterClass.TextRequest
import org.megras.util.embeddings.ClipServiceOuterClass.ImageRequest
import org.megras.util.embeddings.ClipServiceOuterClass.EmbeddingResponse

import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * gRPC client for the ClipService, allowing communication with the Python CLIP embedding server.
 */
class ClipEmbedderClient(private val channel: ManagedChannel) : Closeable {

    private val stub: ClipServiceCoroutineStub = ClipServiceCoroutineStub(channel)

    /**
     * Secondary constructor to create a client connected to a specific host and port.
     * @param host The hostname or IP address of the gRPC server.
     * @param port The port the gRPC server is listening on.
     */
    constructor(host: String, port: Int) : this(
        ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext() // Use plaintext for local development. For production, use TLS.
            .executor(Dispatchers.Default.asExecutor()) // Use a Coroutine Dispatcher for the gRPC executor
            .build()
    )

    /**
     * Gets text embeddings from the gRPC server.
     * @param text The input text string to embed.
     * @return A list of floats representing the text embedding.
     * @throws Exception if an error occurs during the gRPC call.
     */
    suspend fun getTextEmbedding(text: String): List<Float> {
        val request = TextRequest.newBuilder().setText(text).build()
        try {
            val response: EmbeddingResponse = stub.getTextEmbedding(request)
            return response.embeddingList
        } catch (e: Exception) {
            println("Error calling getTextEmbedding: ${e.message}")
            throw e
        }
    }

    /**
     * Gets image embeddings from the gRPC server.
     * This function now takes an image file path as a string.
     * @param imagePath The file path (as a string) to the image file.
     * @return A list of floats representing the image embedding.
     * @throws Exception if an error occurs during file reading or the gRPC call.
     */
    suspend fun getImageEmbedding(imagePath: String): List<Float> {
        try {
            // Read all bytes from the specified image file
            val imageFile = File(imagePath)
            if (!imageFile.exists()) {
                throw IllegalArgumentException("Image file not found at path: $imagePath")
            }
            val imageBytes = imageFile.readBytes()

            // Build the ImageRequest message with the image bytes
            val request = ImageRequest.newBuilder().setImageData(com.google.protobuf.ByteString.copyFrom(imageBytes)).build()

            // Call the gRPC method and get the response
            val response: EmbeddingResponse = stub.getImageEmbedding(request)
            return response.embeddingList
        } catch (e: Exception) {
            println("Error calling getImageEmbedding for path '$imagePath': ${e.message}")
            throw e
        }
    }

    /**
     * Closes the gRPC channel gracefully.
     */
    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}