package org.megras.util.services

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import org.megras.util.services.OcrServiceGrpcKt.OcrServiceCoroutineStub
import org.megras.util.services.OcrServiceOuterClass.RecognizeTextRequest
import org.megras.util.services.OcrServiceOuterClass.RecognizeTextResponse

import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * gRPC client for the OCRService, allowing communication with the Python OCR server.
 */
class OcrClient(private val channel: ManagedChannel) : Closeable {

    // Create a coroutine stub for the OCR service
    private val stub: OcrServiceCoroutineStub = OcrServiceCoroutineStub(channel)

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
     * Recognizes text from an image file using the gRPC server.
     * This function takes an image file path as a string.
     * @param imagePath The file path (as a string) to the image file.
     * @return The recognized text as a string.
     * @throws Exception if an error occurs during file reading or the gRPC call.
     */
    suspend fun recognizeText(imagePath: String): String {
        try {
            // Read all bytes from the specified image file
            val imageFile = File(imagePath)
            if (!imageFile.exists()) {
                throw IllegalArgumentException("Image file not found at path: $imagePath")
            }
            val imageBytes = imageFile.readBytes()

            // Build the RecognizeTextRequest message with the image bytes
            val request = RecognizeTextRequest.newBuilder().setImageData(com.google.protobuf.ByteString.copyFrom(imageBytes)).build()

            // Call the gRPC method and get the response
            val response: RecognizeTextResponse = stub.recognizeText(request)
            return response.recognizedText
        } catch (e: Exception) {
            println("Error calling recognizeText for path '$imagePath': ${e.message}")
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