package org.megras.segmentation.type

import org.megras.segmentation.Bounds
import org.megras.segmentation.SegmentationType

abstract class ReductionSegmentation(val selection: List<String>) : Segmentation {
    override var bounds: Bounds = Bounds()

    override fun equivalentTo(rhs: Segmentation): Boolean {
        if (rhs !is ReductionSegmentation) return false
        return this.contains(rhs) && rhs.contains(this)
    }

    override fun contains(rhs: Segmentation): Boolean {
        if (rhs !is ReductionSegmentation) return false
        return rhs.selection.all { this.selection.contains(it) }
    }

    override fun contains(rhs: Bounds): Boolean = false

    override fun orthogonalTo(rhs: Segmentation): Boolean {
        return rhs !is ReductionSegmentation
    }
}

class StreamChannel(selection: List<String>) : ReductionSegmentation(selection) {
    override val segmentationType: SegmentationType = SegmentationType.CHANNEL

    override fun getDefinition(): String = selection.joinToString(",")
}

class ColorChannel(selection: List<String>) : ReductionSegmentation(selection) {
    override val segmentationType: SegmentationType = SegmentationType.COLOR

    override fun getDefinition(): String = selection.joinToString(",")
}