package org.megras.graphstore.derived.handlers

import kotlinx.coroutines.runBlocking
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.graph.StringValue
import org.megras.data.graph.URIValue
import org.megras.data.mime.MimeType
import org.megras.graphstore.QuadSet
import org.megras.graphstore.derived.DerivedRelationHandler
import org.megras.util.Constants
import org.megras.util.FileUtil
import org.megras.util.services.OcrClient
import kotlin.system.exitProcess

class OcrHandler(private val quadSet: QuadSet, private val objectStore: FileSystemObjectStore) : DerivedRelationHandler<StringValue> {
    override val predicate: URIValue = getPredicate()

    companion object {
        fun getPredicate(): URIValue {
            return URIValue("${Constants.DERIVED_PREFIX}/ocr")
        }
    }

    override fun canDerive(subject: URIValue): Boolean {
        val osr = FileUtil.getOsr(subject, this.quadSet, this.objectStore) ?: return false

        return when (osr.descriptor.mimeType) { //TODO technically, video should also be supported
            MimeType.BMP,
            MimeType.GIF,
            MimeType.JPEG_I,
            MimeType.PNG,
            MimeType.TIFF -> true

            else -> false
        }
    }

    override fun derive(subject: URIValue): Collection<StringValue> {
        val path = FileUtil.getPath(subject, this.quadSet, this.objectStore) ?: return emptyList()

        val recognizedText: String = runBlocking {
            val client = OcrClient("localhost", 50051)
            try {
                val recognizedText: String = client.recognizeText(path)
                return@runBlocking recognizedText
            } catch (e: Exception) {
                println("Error getting OCR for '$path': ${e.message}")
                return@runBlocking ""
            } finally {
                client.close()
            }
        }

        if (recognizedText.isBlank()) {
            return emptyList()
        }

        return listOf(
            StringValue(recognizedText)
        )
    }
}