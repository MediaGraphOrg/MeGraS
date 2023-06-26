package org.megras.segmentation.media

import org.megras.segmentation.Bounds
import org.megras.segmentation.type.Character
import org.megras.segmentation.type.Segmentation
import org.slf4j.LoggerFactory
import java.io.InputStream


object TextSegmenter {

    private val logger = LoggerFactory.getLogger(this.javaClass)

    fun segment(text: InputStream, segmentation: Segmentation): SegmentationResult? = try {
        if (segmentation is Character) {
            val textBytes = text.readBytes()

            val indices = mutableListOf<Int>()
            segmentation.intervals.forEach { i ->
                if (i.low < textBytes.size && i.high <= textBytes.size) {
                    (i.low.toInt() until i.high.toInt()).forEach { j -> indices.add(j) }
                } else {
                    return null
                }
            }
            val segment = textBytes.sliceArray(indices)
            SegmentationResult(segment, Bounds().addT(0, segment.size))
        } else {
            logger.warn("Segmentation type '${segmentation.getType()}' not applicable to text")
            null
        }
    } catch (e: Exception) {
        logger.error("Error while segmenting text: ${e.localizedMessage}")
        null
    }
}