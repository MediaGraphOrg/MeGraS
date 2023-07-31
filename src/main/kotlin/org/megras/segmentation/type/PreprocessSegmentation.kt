package org.megras.segmentation.type

import org.megras.segmentation.Bounds

interface PreprocessSegmentation {
    fun preprocess(bounds: Bounds): Segmentation?
}