package org.megras.util.services

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import org.megras.util.services.DoclingServiceGrpcKt.DoclingServiceCoroutineStub
import org.megras.util.services.DoclingServiceOuterClass.PdfRequest

import java.io.Closeable
import java.io.File

/**
 * gRPC client for the DoclingService, allowing communication with the Python Docling server.
 */
class DoclingClient(private val channel: ManagedChannel) : Closeable {

    companion object {
        private val channelCache = java.util.concurrent.ConcurrentHashMap<String, ManagedChannel>()

        fun getChannel(host: String, port: Int): ManagedChannel {
            return channelCache.computeIfAbsent("$host:$port") {
                ManagedChannelBuilder.forAddress(host, port)
                    .usePlaintext()
                    .executor(Dispatchers.Default.asExecutor())
                    .build()
            }
        }
    }

    private val stub: DoclingServiceCoroutineStub = DoclingServiceCoroutineStub(channel)

    /**
     * Secondary constructor to create a client connected to a specific host and port.
     * @param host The hostname or IP address of the gRPC server.
     * @param port The port the gRPC server is listening on.
     */
    constructor(host: String, port: Int) : this(getChannel(host, port))

    /**
     * Extracts plain text from a PDF file using the Docling service.
     * @param pdfPath Path to the PDF file.
     * @return Extracted plain text.
     */
    suspend fun extractText(pdfPath: String): String {
        val bytes = readPdfBytes(pdfPath)
        val request = PdfRequest.newBuilder()
            .setPdfData(com.google.protobuf.ByteString.copyFrom(bytes))
            .build()
        return try {
            val response = stub.extractText(request)
            response.text
        } catch (e: Exception) {
            println("Error calling extractText for '$pdfPath': ${e.message}")
            throw e
        }
    }

    /**
     * Extracts the full Docling JSON as a raw string; caller parses for figures/tables.
     */
    suspend fun extractDocJson(pdfPath: String): String {
        val bytes = readPdfBytes(pdfPath)
        val request = PdfRequest.newBuilder()
            .setPdfData(com.google.protobuf.ByteString.copyFrom(bytes))
            .build()
        return try {
            val response = stub.extractDocJson(request)
            response.json
        } catch (e: Exception) {
            println("Error calling extractDocJson for '$pdfPath': ${e.message}")
            throw e
        }
    }

    private fun readPdfBytes(pdfPath: String): ByteArray {
        val pdfFile = File(pdfPath)
        require(pdfFile.exists()) { "PDF file not found at path: $pdfPath" }
        return pdfFile.readBytes()
    }

    /**
     * Closes the gRPC channel gracefully.
     */
    override fun close() {
        // Shared channel — do not shut down per-client
    }
}
