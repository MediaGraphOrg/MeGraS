package org.megras.util.embeddings.client

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.runBlocking
import java.io.File // Required if you *ever* want to read local files for sending
import java.util.concurrent.TimeUnit
import org.slf4j.Logger
import org.slf4j.LoggerFactory

// Import the generated gRPC and Protobuf classes
import org.megras.util.embeddings.client.ClipServiceGrpcKt
import org.megras.util.embeddings.client.ClipServiceOuterClass.ImageRequest
import org.megras.util.embeddings.client.ClipServiceOuterClass.TextRequest

class ClipClient(private val channel: ManagedChannel) {
    companion object {
        val logger: Logger = LoggerFactory.getLogger(ClipClient::class.java)
    }

    // Using the suspending stub for coroutine support
    private val stub: ClipServiceGrpcKt.ClipServiceCoroutineStub =
        ClipServiceGrpcKt.ClipServiceCoroutineStub(channel)

    suspend fun getTextEmbedding(text: String): List<Float>? {
        logger.info("Requesting text embedding for: \"{}\"", text)
        val request = TextRequest.newBuilder().setText(text).build()
        try {
            val response = stub.getTextEmbedding(request)
            logger.info("Received text embedding (first 5 values): {}", response.embeddingList.take(5))
            return response.embeddingList
        } catch (e: StatusRuntimeException) {
            logger.error("RPC failed: {0}", e.status)
            return null
        }
    }

    // This method now directly accepts image bytes (ByteArray)
    suspend fun getImageEmbedding(imageData: ByteArray): List<Float>? {
        logger.info("Requesting image embedding for provided image bytes (size: ${imageData.size} bytes).")
        // Convert Kotlin's ByteArray to Google Protobuf's ByteString
        val request = ImageRequest.newBuilder().setImageData(com.google.protobuf.ByteString.copyFrom(imageData)).build()
        try {
            val response = stub.getImageEmbedding(request)
            logger.info("Received image embedding (first 5 values): {}", response.embeddingList.take(5))
            return response.embeddingList
        } catch (e: StatusRuntimeException) {
            logger.error("RPC failed: {0}", e.status)
            return null
        }
    }

    fun shutdown() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}

fun main() = runBlocking(Dispatchers.Default) {
    val host = "localhost" // Or the IP address of your Python server
    val port = 50051

    val channel = ManagedChannelBuilder.forAddress(host, port)
        .usePlaintext() // Use plain-text for local development (no SSL)
        .executor(Dispatchers.Default.asExecutor()) // Use Kotlin coroutine dispatcher for gRPC
        .build()

    val client = ClipClient(channel)

    try {
        val textString = "A baseball player"

        val textEmbed = client.getTextEmbedding(textString)
        if (textEmbed != null) {
            println("\nText Embedding (size ${textEmbed.size}):")
            println(textEmbed.take(10).joinToString(", ")) // Print first 10 values
        }

    } finally {
        client.shutdown()
    }
}