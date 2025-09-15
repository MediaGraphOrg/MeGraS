package org.megras.graphstore.derived.handlers

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.fs.file.PseudoFile
import org.megras.data.graph.LocalQuadValue
import org.megras.data.graph.LongValue
import org.megras.data.graph.Quad
import org.megras.data.graph.StringValue
import org.megras.data.graph.URIValue
import org.megras.data.model.MediaType
import org.megras.data.schema.MeGraS
import org.megras.graphstore.MutableQuadSet
import org.megras.graphstore.QuadSet
import org.megras.graphstore.derived.DerivedRelationHandler
import org.megras.graphstore.derived.QuadSetAware
import org.megras.segmentation.Bounds
import org.megras.util.Constants
import org.megras.util.FileUtil
import java.io.File

class ParagraphHandler(private val quadSet: QuadSet, private val objectStore: FileSystemObjectStore) : DerivedRelationHandler<LocalQuadValue>, QuadSetAware {
    override val predicate: URIValue = getPredicate()

    private var effectiveQuadSet: QuadSet = quadSet

    override fun setQuadSet(quadSet: QuadSet) {
        this.effectiveQuadSet = quadSet
    }

    companion object {
        fun getPredicate(): URIValue {
            return URIValue("${Constants.DERIVED_PREFIX}/paragraph")
        }
    }

    override fun canDerive(subject: URIValue): Boolean {
        val osr = FileUtil.getOsr(subject, this.effectiveQuadSet, this.objectStore) ?: return false
        return when (MediaType.mimeTypeMap[osr.descriptor.mimeType]) {
            MediaType.DOCUMENT -> true
            else -> false
        }
    }

    override fun derive(subject: URIValue): Collection<LocalQuadValue> {
        val path = FileUtil.getPath(subject, this.effectiveQuadSet, this.objectStore) ?: return emptyList()

        // Obtain cached Docling JSON via derived relation
        val docJsonPredicate = DocumentModelJsonHandler.getPredicate()
        val json: String = (effectiveQuadSet
            .filter(listOf(subject), listOf(docJsonPredicate), null)
            .filterPredicate(docJsonPredicate)
            .firstOrNull()?.`object` as? StringValue)?.value ?: ""

        val texts: List<Map<String, Any?>> = try {
            if (json.isBlank()) emptyList() else {
                val mapper = ObjectMapper().registerKotlinModule()
                @Suppress("UNCHECKED_CAST")
                val root = mapper.readValue(json, Map::class.java) as Map<String, Any?>
                (root["texts"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
            }
        } catch (e: Exception) {
            println("Error parsing texts for '$path': ${e.message}")
            emptyList()
        }

        if (texts.isEmpty()) {
            return emptyList()
        }

        val baseName = File(path).nameWithoutExtension.ifBlank { "document" }
        val created = mutableListOf<LocalQuadValue>()
        val mqs = effectiveQuadSet as MutableQuadSet

        texts.forEachIndexed { idx, item ->
            val textStr = (item["text"] as? String)?.ifBlank { null } ?: (item["orig"] as? String)
            if (textStr.isNullOrBlank()) return@forEachIndexed

            val prov = (item["prov"] as? List<*>)?.firstOrNull() as? Map<*, *>
            val pageNo = (prov?.get("page_no") as? Number)?.toInt()
            val bbox = prov?.get("bbox") as? Map<*, *>
            val l = (bbox?.get("l") as? Number)?.toDouble()
            val r = (bbox?.get("r") as? Number)?.toDouble()
            val t = (bbox?.get("t") as? Number)?.toDouble()
            val b = (bbox?.get("b") as? Number)?.toDouble()

            val reference = item["self_ref"]?.toString() ?: ""
            val ordinal = reference.substringAfterLast("/").toLongOrNull()
            val label = item["label"]?.toString()?.ifBlank { null }

            val pageIndex = pageNo?.let { it - 1 } ?: 0

            // Create paragraph text file
            val nameOrdinal = ordinal ?: idx.toLong()
            val outName = "${baseName}-page${pageIndex + 1}-paragraph${nameOrdinal}.txt"
            val bytes = textStr.toByteArray(Charsets.UTF_8)
            val pseudo = PseudoFile(bytes, outName)

            val id = FileUtil.addFile(objectStore, mqs, pseudo, metaSkip = true)

            // Attach segment metadata back to the source PDF
            val metaQuads = mutableListOf(
                Quad(id, MeGraS.SEGMENT_OF.uri, subject)
            )

            if (pageNo != null) {
                metaQuads.add(
                    Quad(id, URIValue(Constants.NLP_PREFIX + "/pageNumber"), LongValue(pageNo.toLong()))
                )
            }

            if (l != null && r != null && t != null && b != null) {
                metaQuads.add(
                    Quad(
                        id,
                        MeGraS.SEGMENT_BOUNDS.uri,
                        StringValue(Bounds("$l,$r,$t,$b,-,-,$pageIndex,$pageIndex").toString())
                    )
                )
            }
            if (reference.isNotBlank()) {
                metaQuads.add(Quad(id, URIValue(Constants.NLP_PREFIX + "/reference"), StringValue(reference)))
            }
            if (ordinal != null) {
                metaQuads.add(Quad(id, URIValue(Constants.NLP_PREFIX + "/ordinal"), LongValue(ordinal)))
            }
            if (!label.isNullOrBlank()) {
                metaQuads.add(Quad(id, URIValue(Constants.NLP_PREFIX + "/label"), StringValue(label)))
            } else {
                metaQuads.add(Quad(id, URIValue(Constants.NLP_PREFIX + "/label"), StringValue("paragraph")))
            }

            mqs.addAll(metaQuads)
            created.add(id)
        }

        return created
    }
}

