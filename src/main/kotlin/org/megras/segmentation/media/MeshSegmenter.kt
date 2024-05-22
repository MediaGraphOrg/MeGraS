package org.megras.segmentation.media

import de.javagl.obj.ObjReader
import de.javagl.obj.ObjWriter
import org.megras.segmentation.SegmentationType
import org.megras.segmentation.type.MeshBody
import org.megras.segmentation.type.Segmentation
import org.megras.util.ObjUtil
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream


object MeshSegmenter {

    private val logger = LoggerFactory.getLogger(this.javaClass)

    fun segment(inputStream: InputStream, segmentation: Segmentation): SegmentationResult? = try {
        when(segmentation.segmentationType) {
            SegmentationType.MESH -> cut(inputStream, segmentation as MeshBody)
            else -> {
                logger.warn("Segmentation type '${segmentation.getType()}' not applicable to 3D mesh")
                null
            }
        }
    } catch (e: Exception) {
        logger.error("Error while segmenting 3D mesh: ${e.localizedMessage}")
        null
    }

    private fun cut(inputStream: InputStream, segmentation: MeshBody): SegmentationResult {
        val obj = ObjReader.read(inputStream)

        val objCSG = ObjUtil.objToCSG(obj)
        val segmentCSG = segmentation.objToCSG()
        val resultCSG = objCSG.intersect(segmentCSG)
        val resultString = resultCSG.toObj().obj
        val result = ObjReader.read(resultString.byteInputStream())

        val out = ByteArrayOutputStream()
        ObjWriter.write(result, out)
        return SegmentationResult(out.toByteArray(), ObjUtil.computeBounds(result))
    }
}