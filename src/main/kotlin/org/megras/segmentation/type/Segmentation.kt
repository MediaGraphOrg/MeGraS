package org.megras.segmentation.type

import org.megras.segmentation.Bounds
import org.megras.segmentation.SegmentationType

enum class TranslateDirection {
    POSITIVE,
    NEGATIVE
}

sealed interface Segmentation {
    val segmentationType: SegmentationType?
    var bounds: Bounds

    /**
     * Attempts to compare equivalence of this segmentation to another one.
     * In cases where the segmentations could be equivalent, but not enough information is available, `false` is returned.
     */
    fun equivalentTo(rhs: Segmentation): Boolean

    /**
     * Attempts to determine if another segmentation is contained in this one.
     * In cases where the segmentations could be containing, but not enough information is available, `false` is returned.
     */
    fun contains(rhs: Segmentation): Boolean

    /**
     * Checks if the segmentation fully contains the medium
     */
    fun contains(rhs: Bounds): Boolean

    /**
     * Attempts to determine if this segmentation intersects another one.
     * In cases where the segmentations could be intersecting, but not enough information is available, `false` is returned.
     */
    fun orthogonalTo(rhs: Segmentation): Boolean {
        return this.bounds.orthogonalTo(rhs.bounds)
    }

    fun within(rhs: Segmentation): Boolean {
        return rhs.contains(this) && (this.getDefinition() != rhs.getDefinition())
    }

    fun covers(rhs: Segmentation): Boolean {
        return rhs.contains(this)
    }

    fun beside(rhs: Segmentation): Boolean {
        return ((this.left(rhs) || this.right(rhs)) && !(this.above(rhs) || this.below(rhs)))
    }

    fun disjoint(rhs: Segmentation): Boolean{
        return !this.orthogonalTo(rhs)
    }

    fun overlaps(rhs: Segmentation): Boolean {
        return this.orthogonalTo(rhs)
    }

    fun above(rhs: Segmentation): Boolean {
        TODO("Not yet implemented")
    }

    fun below(rhs: Segmentation): Boolean {
        TODO("Not yet implemented")
    }

    fun crosses(rhs: Segmentation): Boolean {
        TODO("Not yet implemented")
    }

    fun left(rhs: Segmentation): Boolean {
        TODO("Not yet implemented")
    }

    fun right(rhs: Segmentation): Boolean {
        TODO("Not yet implemented")
    }

    fun touches(rhs: Segmentation): Boolean {
        TODO("Not yet implemented")
    }

    fun translate(by: Bounds, direction: TranslateDirection = TranslateDirection.POSITIVE): Segmentation {
        return this
    }

    fun getType(): String = segmentationType?.name?.lowercase() ?: ""

    fun getDefinition(): String

    fun toURI() = "segment/" + getType() + "/" + getDefinition()
}