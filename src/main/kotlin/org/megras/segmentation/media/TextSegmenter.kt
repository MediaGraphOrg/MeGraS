package org.megras.segmentation.media

import org.apache.commons.io.IOUtils
import org.megras.segmentation.Bounds
import org.megras.segmentation.type.Character
import org.megras.segmentation.type.Segmentation
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.nio.charset.Charset


object TextSegmenter {

    private val logger = LoggerFactory.getLogger(this.javaClass)

    fun segment(text: InputStream, segmentation: Segmentation): SegmentationResult? = try {
        if (segmentation is Character) {
            val textString = IOUtils.toString(text, Charset.defaultCharset())
            val charList = textString.toCharArray()

            val lower = segmentation.bounds.getMinT()
            val upper = segmentation.bounds.getMaxT()

            segmentation.getIntervalsToDiscard().forEach { i ->
                if (i.low < textString.length && i.high <= textString.length) {
                    (i.low.toInt() until i.high.toInt()).forEach { j -> charList[j] = ' ' }
                }
            }

            val segment = String(charList).substring(lower.toInt(), upper.toInt())
            SegmentationResult(segment.encodeToByteArray(), Bounds().addT(0, segment.length))
        } else {
            logger.warn("Segmentation type '${segmentation.getType()}' not applicable to text")
            null
        }
    } catch (e: Exception) {
        logger.error("Error while segmenting text: ${e.localizedMessage}")
        null
    }
}