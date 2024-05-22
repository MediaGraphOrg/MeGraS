package org.megras.segmentation.type

import org.megras.segmentation.Bounds

interface RelativeSegmentation {
    val isRelative: Boolean

    fun toAbsolute(bounds: Bounds): Segmentation?
}