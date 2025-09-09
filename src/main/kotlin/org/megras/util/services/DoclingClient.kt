package org.megras.util.services

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import org.megras.util.services.DoclingServiceGrpcKt.DoclingServiceCoroutineStub
import org.megras.util.services.DoclingServiceOuterClass.PdfRequest

import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit
import com.google.protobuf.ListValue
import com.google.protobuf.Struct
import com.google.protobuf.Value

/**
 * gRPC client for the DoclingService, allowing communication with the Python Docling server.
 */
class DoclingClient(private val channel: ManagedChannel) : Closeable {

    private val stub: DoclingServiceCoroutineStub = DoclingServiceCoroutineStub(channel)

    /**
     * Secondary constructor to create a client connected to a specific host and port.
     * @param host The hostname or IP address of the gRPC server.
     * @param port The port the gRPC server is listening on.
     */
    constructor(host: String, port: Int) : this(
        ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext() // For local development; use TLS in production
            .executor(Dispatchers.Default.asExecutor())
            .build()
    )

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
     * Extracts figures metadata as a protobuf ListValue (JSON array of objects).
     * @param pdfPath Path to the PDF file.
     * @return ListValue representing an array of figure objects.
     */
    suspend fun extractFigures(pdfPath: String): ListValue {
        val bytes = readPdfBytes(pdfPath)
        val request = PdfRequest.newBuilder()
            .setPdfData(com.google.protobuf.ByteString.copyFrom(bytes))
            .build()
        return try {
            val response = stub.extractFigures(request)
            response.figures
        } catch (e: Exception) {
            println("Error calling extractFigures for '$pdfPath': ${e.message}")
            throw e
        }
    }

    /**
     * Extracts tables metadata as a protobuf ListValue (JSON array of objects).
     * @param pdfPath Path to the PDF file.
     * @return ListValue representing an array of table objects.
     */
    suspend fun extractTables(pdfPath: String): ListValue {
        val bytes = readPdfBytes(pdfPath)
        val request = PdfRequest.newBuilder()
            .setPdfData(com.google.protobuf.ByteString.copyFrom(bytes))
            .build()
        return try {
            val response = stub.extractTables(request)
            response.tables
        } catch (e: Exception) {
            println("Error calling extractTables for '$pdfPath': ${e.message}")
            throw e
        }
    }

    /**
     * Extracts figures as a Kotlin list of maps for convenient consumption.
     */
    suspend fun extractFiguresAsMaps(pdfPath: String): List<Map<String, Any?>> {
        val lv = extractFigures(pdfPath)
        return toKotlinList(lv).map {
            @Suppress("UNCHECKED_CAST")
            it as? Map<String, Any?> ?: emptyMap()
        }
    }

    /**
     * Extracts tables as a Kotlin list of maps for convenient consumption.
     */
    suspend fun extractTablesAsMaps(pdfPath: String): List<Map<String, Any?>> {
        val lv = extractTables(pdfPath)
        return toKotlinList(lv).map {
            @Suppress("UNCHECKED_CAST")
            it as? Map<String, Any?> ?: emptyMap()
        }
    }

    private fun toKotlinList(listValue: ListValue): List<Any?> {
        return listValue.valuesList.map { toKotlinValue(it) }
    }

    private fun toKotlinStruct(struct: Struct): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        for ((k, v) in struct.fieldsMap) {
            result[k] = toKotlinValue(v)
        }
        return result
    }

    private fun toKotlinValue(value: Value): Any? = when (value.kindCase) {
        Value.KindCase.NULL_VALUE -> null
        Value.KindCase.BOOL_VALUE -> value.boolValue
        Value.KindCase.NUMBER_VALUE -> value.numberValue
        Value.KindCase.STRING_VALUE -> value.stringValue
        Value.KindCase.STRUCT_VALUE -> toKotlinStruct(value.structValue)
        Value.KindCase.LIST_VALUE -> toKotlinList(value.listValue)
        Value.KindCase.KIND_NOT_SET, null -> null
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
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}
