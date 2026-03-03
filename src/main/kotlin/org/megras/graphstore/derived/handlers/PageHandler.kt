package org.megras.graphstore.derived.handlers

import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.graph.LocalQuadValue
import org.megras.data.graph.URIValue
import org.megras.data.model.MediaType
import org.megras.graphstore.MutableQuadSet
import org.megras.graphstore.QuadSet
import org.megras.graphstore.derived.DerivedRelationHandler
import org.megras.segmentation.BoundsUtil
import org.megras.segmentation.SegmentationUtil
import org.megras.segmentation.type.Interval
import org.megras.segmentation.type.Page
import org.megras.util.Constants
import org.megras.util.FileUtil

class PageHandler(private val quadSet: QuadSet, private val objectStore: FileSystemObjectStore) : DerivedRelationHandler<LocalQuadValue> {
    override val predicate: URIValue = getPredicate()

    companion object {
        fun getPredicate(): URIValue {
            return URIValue("${Constants.DERIVED_PREFIX}/page")
        }
    }

    override fun canDerive(subject: URIValue): Boolean {
        val osr = FileUtil.getOsr(subject, this.quadSet, this.objectStore) ?: return false

        return when (MediaType.mimeTypeMap[osr.descriptor.mimeType]) {
            MediaType.DOCUMENT -> true
            else -> false
        }
    }

    override fun derive(subject: URIValue): Collection<LocalQuadValue> {
        val bounds = BoundsUtil.getBounds(subject, this.quadSet)
            ?: throw IllegalArgumentException("Invalid subject. No bounds found for $subject")
        val start = bounds.getMinT().toInt()
        val end = bounds.getMaxT().toInt()
        val pageQuads = mutableListOf<LocalQuadValue>()
        for (i in start until end) {
            val p = i.toDouble()
            val segmentation = Page(listOf(Interval(p, p)))
            pageQuads.add(SegmentationUtil.segment(subject, segmentation, objectStore, quadSet as MutableQuadSet))
        }

        return pageQuads
    }
}