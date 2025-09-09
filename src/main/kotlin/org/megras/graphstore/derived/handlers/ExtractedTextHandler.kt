package org.megras.graphstore.derived.handlers

import kotlinx.coroutines.runBlocking
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.fs.file.PseudoFile
import org.megras.data.graph.QuadValue
import org.megras.data.graph.StringValue
import org.megras.data.graph.URIValue
import org.megras.data.model.MediaType
import org.megras.graphstore.MutableQuadSet
import org.megras.graphstore.QuadSet
import org.megras.graphstore.derived.DerivedRelationHandler
import org.megras.util.Constants
import org.megras.util.FileUtil
import org.megras.util.ServiceConfig
import org.megras.util.services.DoclingClient
import java.io.File

class ExtractedTextHandler(private val quadSet: QuadSet, private val objectStore: FileSystemObjectStore) : DerivedRelationHandler<QuadValue> {
    override val predicate: URIValue = getPredicate()

    companion object {
        fun getPredicate(): URIValue {
            return URIValue("${Constants.DERIVED_PREFIX}/extractedText")
        }
    }

    override fun canDerive(subject: URIValue): Boolean {
        val osr = FileUtil.getOsr(subject, this.quadSet, this.objectStore) ?: return false

        return when (MediaType.mimeTypeMap[osr.descriptor.mimeType]) {
            MediaType.DOCUMENT -> true
            MediaType.TEXT -> true
            else -> false
        }
    }

    override fun derive(subject: URIValue): Collection<QuadValue> {
        val path = FileUtil.getPath(subject, this.quadSet, this.objectStore) ?: return emptyList()
        val osr = FileUtil.getOsr(subject, this.quadSet, this.objectStore) ?: return emptyList()


        return when (MediaType.mimeTypeMap[osr.descriptor.mimeType]) {
            MediaType.DOCUMENT -> {
                val extractedText: String = runBlocking {
                    val client = DoclingClient(ServiceConfig.grpcHost, ServiceConfig.grpcPort)
                    try {
                        val extractedText: String = client.extractText(path)
                        return@runBlocking extractedText
                    } catch (e: Exception) {
                        println("Error extracting text from '$path': ${e.message}")
                        return@runBlocking ""
                    } finally {
                        client.close()
                    }
                }

                if (extractedText.isBlank()) {
                    return emptyList()
                }

                // Create a temporary .txt file representation and add it to the object store
                val srcName = File(path).nameWithoutExtension.ifBlank { "document" }
                val outName = "${srcName}-extracted.txt"
                val bytes = extractedText.toByteArray(Charsets.UTF_8)
                val pseudo = PseudoFile(bytes, outName)
                val id = FileUtil.addFile(objectStore, quadSet as MutableQuadSet, pseudo)
                listOf(id)
            }
            MediaType.TEXT -> {
                val textFromFile: String = try {
                    File(path).readText(Charsets.UTF_8)
                } catch (e: Exception) {
                    println("Error reading text file '$path': ${e.message}")
                    ""
                }

                if (textFromFile.isBlank()) {
                    emptyList()
                } else {
                    listOf(
                        StringValue(textFromFile)
                    )
                }
            }
            else -> emptyList()
        }
    }
}