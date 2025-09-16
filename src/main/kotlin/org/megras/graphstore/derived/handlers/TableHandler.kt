package org.megras.graphstore.derived.handlers

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
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
import org.megras.util.DocExtractorUtil
import org.megras.util.DocExtractorUtilType
import org.megras.util.FileUtil
import java.io.File
import kotlin.math.max

class TableHandler(
    private val quadSet: QuadSet,
    private val objectStore: FileSystemObjectStore
) : DerivedRelationHandler<LocalQuadValue>, QuadSetAware {

    override val predicate: URIValue = getPredicate()
    private var effectiveQuadSet: QuadSet = quadSet

    override fun setQuadSet(quadSet: QuadSet) {
        this.effectiveQuadSet = quadSet
    }

    companion object {
        fun getPredicate(): URIValue = URIValue("${Constants.DERIVED_PREFIX}/table")
    }

    override fun canDerive(subject: URIValue): Boolean {
        val osr = FileUtil.getOsr(subject, effectiveQuadSet, objectStore) ?: return false
        return when (MediaType.mimeTypeMap[osr.descriptor.mimeType]) {
            MediaType.DOCUMENT -> true
            else -> false
        }
    }

    override fun derive(subject: URIValue): Collection<LocalQuadValue> {
        val created = DocExtractorUtil.getQuadsFromDocJson(subject, DocExtractorUtilType.TABLES, effectiveQuadSet as MutableQuadSet, objectStore)

        return created
    }
}