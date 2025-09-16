package org.megras.graphstore.derived.handlers

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.graph.LocalQuadValue
import org.megras.data.graph.LongValue
import org.megras.data.graph.Quad
import org.megras.data.graph.StringValue
import org.megras.data.graph.URIValue
import org.megras.data.model.MediaType
import org.megras.graphstore.MutableQuadSet
import org.megras.graphstore.QuadSet
import org.megras.graphstore.derived.DerivedRelationHandler
import org.megras.graphstore.derived.QuadSetAware
import org.megras.segmentation.SegmentationUtil
import org.megras.segmentation.type.Interval
import org.megras.segmentation.type.Page
import org.megras.segmentation.type.Polygon
import org.megras.util.Constants
import org.megras.util.DocExtractorUtil
import org.megras.util.DocExtractorUtilType
import org.megras.util.FileUtil

class FigureHandler(
    private val quadSet: QuadSet,
    private val objectStore: FileSystemObjectStore
) : DerivedRelationHandler<LocalQuadValue>, QuadSetAware {

    override val predicate: URIValue = getPredicate()

    private var effectiveQuadSet: QuadSet = quadSet

    override fun setQuadSet(quadSet: QuadSet) {
        this.effectiveQuadSet = quadSet
    }

    companion object {
        fun getPredicate(): URIValue = URIValue("${Constants.DERIVED_PREFIX}/figure")
    }

    override fun canDerive(subject: URIValue): Boolean {
        val osr = FileUtil.getOsr(subject, this.effectiveQuadSet, this.objectStore) ?: return false
        return when (MediaType.mimeTypeMap[osr.descriptor.mimeType]) {
            MediaType.DOCUMENT -> true
            else -> false
        }
    }

    override fun derive(subject: URIValue): Collection<LocalQuadValue> {
        val created = DocExtractorUtil.getQuadsFromDocJson(subject, DocExtractorUtilType.FIGURES, effectiveQuadSet as MutableQuadSet, objectStore)

        return created
    }
}