package org.megras.segmentation.media

import de.javagl.obj.*
import org.megras.segmentation.type.SliceSegmentation
import org.megras.segmentation.type.Segmentation
import org.megras.segmentation.SegmentationType
import org.megras.util.ObjUtil
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream


object MeshSegmenter {

    private val logger = LoggerFactory.getLogger(this.javaClass)

    fun segment(inputStream: InputStream, segmentation: Segmentation): SegmentationResult? = try {
        when(segmentation.segmentationType) {
            SegmentationType.SLICE -> slice(inputStream, segmentation as SliceSegmentation)
            else -> {
                logger.warn("Segmentation type '${segmentation.getType()}' not applicable to 3D mesh")
                null
            }
        }
    } catch (e: Exception) {
        logger.error("Error while segmenting 3D mesh: ${e.localizedMessage}")
        null
    }

    private fun slice(inputStream: InputStream, sliceSegmentation: SliceSegmentation): SegmentationResult {
        val obj = ObjReader.read(inputStream)

        val segmentedObj = ObjUtil.segmentSlice(obj, sliceSegmentation)

        val out = ByteArrayOutputStream()
        ObjWriter.write(segmentedObj, out)
        return SegmentationResult(out.toByteArray(), ObjUtil.computeBounds(segmentedObj))
    }
}