package org.megras.segmentation.media

import org.megras.segmentation.Bounds

data class SegmentationResult(val segment: ByteArray, val bounds: Bounds) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SegmentationResult

        if (!segment.contentEquals(other.segment)) return false
        return bounds == other.bounds
    }

    override fun hashCode(): Int {
        var result = segment.contentHashCode()
        result = 31 * result + bounds.hashCode()
        return result
    }
}
