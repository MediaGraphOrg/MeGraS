package org.megras.segmentation

import de.javagl.obj.ObjReader
import org.megras.data.model.MediaType
import org.megras.segmentation.type.*
import java.io.ByteArrayInputStream
import java.util.*
import javax.imageio.ImageIO

object SegmentationUtil {

    fun shouldSwap(first: SegmentationType?, second: SegmentationType?): Boolean {
        return (first != SegmentationType.TIME && second == SegmentationType.TIME) ||
            (first == SegmentationType.COLOR && second != SegmentationType.COLOR)
    }

    fun parseSegmentationType(name: String): SegmentationType? =
        try {
            SegmentationType.valueOf(name.uppercase())
        } catch (e: IllegalArgumentException) {
            null //not found
        }

    fun parseSegmentation(segmentType: String, segmentDefinition: String, mediaType: MediaType? = null): Segmentation? {

        val type = parseSegmentationType(segmentType)
        return when (type) {
            SegmentationType.RECT -> {
                val coords = segmentDefinition.split(",").mapNotNull {
                    it.trim().toDoubleOrNull()
                }

                if (coords.size == 4) {
                    Rect(coords[0], coords[1], coords[2], coords[3])
                } else {
                    null
                }
            }

            /**
             * (x,y),(x,y),...,(x,y) or x,y,x,y,...,x,y
             */
            SegmentationType.POLYGON -> {
                val points = parsePointPairs(segmentDefinition)

                if (points != null) {
                    Polygon(points)
                } else {
                    null
                }
            }
            SegmentationType.BEZIER -> {
                val points = parsePointPairs(segmentDefinition)

                if (points != null) {
                    BezierSpline(points)
                } else {
                    null
                }
            }
            SegmentationType.BSPLINE -> {
                val points = parsePointPairs(segmentDefinition)

                if (points != null) {
                    BSpline(points)
                } else {
                    null
                }
            }

            SegmentationType.PATH -> {
                SVGPath(segmentDefinition)
            }

            SegmentationType.MASK -> {
                try {
                    val decoded = Base64.getUrlDecoder().decode(segmentDefinition)
                    val maskImage = ImageIO.read(ByteArrayInputStream(decoded))
                    ImageMask(maskImage)
                } catch (e: Exception) {
                    null
                }
            }

            SegmentationType.HILBERT -> {
                val elements = segmentDefinition.split(",")
                val order = elements[0].toIntOrNull()

                val ranges = mutableListOf<Interval>()
                elements.forEach { el ->
                    val range = el.trim().split("-").map { it.trim().toDoubleOrNull() ?: return null }
                    when (range.size) {
                        1 -> ranges.add(Interval(range[0], range[0]))
                        2 -> ranges.add(Interval(range[0], range[1]))
                        else -> return null
                    }
                }

                ranges.removeAt(0) // order

                if (order != null) {
                    Hilbert(order, ranges)
                } else {
                    null
                }
            }

            SegmentationType.CHANNEL -> {
                val channels = segmentDefinition.split(",").map { it.trim() }
                StreamChannel(channels)
            }

            SegmentationType.COLOR -> {
                val colors = segmentDefinition.split(",").map { it.trim() }
                ColorChannel(colors)
            }

            SegmentationType.FREQUENCY -> {
                val intervals = parseIntervals(segmentDefinition) ?: return null

                if (intervals.size == 1) {
                    Frequency(intervals[0])
                } else {
                    null
                }
            }

            SegmentationType.TIME -> {
                val intervals = parseIntervals(segmentDefinition) ?: return null
                Time(intervals.map { Interval(it.low * 1000, it.high * 1000) })
            }

            SegmentationType.CHARACTER -> {
                val intervals = parseIntervals(segmentDefinition) ?: return null
                Character(intervals)
            }

            SegmentationType.PAGE -> {
                val intervals = parseIntervals(segmentDefinition) ?: return null
                Page(intervals)
            }

            SegmentationType.WIDTH -> {
                val intervals = parseIntervals(segmentDefinition) ?: return null
                Width(intervals)
            }

            SegmentationType.HEIGHT -> {
                val intervals = parseIntervals(segmentDefinition) ?: return null
                Height(intervals)
            }

            SegmentationType.SLICE -> {
                val params = segmentDefinition.split(",").map {
                    it.trim().toDoubleOrNull() ?: return null
                }

                when (params.size) {
                    4 -> SliceSegmentation(params[0], params[1], 0.0, params[2], params[3] == 1.0)
                    5 -> SliceSegmentation(params[0], params[1], params[2], params[3], params[4] == 1.0)
                    else -> null
                }
            }

            /**
             * t0,type,description;t1,type,description;t2,type,description
             * type and description follow the other guidelines, e.g. rect,0,1,0,1
             */
            SegmentationType.ROTOSCOPE -> {
                val rotoscopeList = mutableListOf<RotoscopePair>()

                segmentDefinition.split(";").forEach { part ->
                    val p = part.split(",")
                    val time = p[0].trim().toDoubleOrNull()
                    val segmentationType = p[1].trim()
                    val segmentationDescription = part.substringAfter("$segmentationType,")

                    val segmentation = parseSegmentation(segmentationType, segmentationDescription)
                    if (time != null && segmentation is TwoDimensionalSegmentation) {
                        rotoscopeList.add(RotoscopePair(time * 1000, segmentation))
                    } else {
                        return null
                    }
                }
                Rotoscope(rotoscopeList)
            }

            SegmentationType.MESH -> {
                try {
                    val objDescription = segmentDefinition.replace(",", "\n")
                    val obj = ObjReader.read(objDescription.byteInputStream())
                    val segment = MeshBody(obj)
                    if (mediaType != null && mediaType == MediaType.VIDEO) {
                        segment.bounds.toTemporal()
                    }
                    segment
                } catch (e: Exception) {
                    null
                }
            }

            else -> null
        }
    }

    private fun parseIntervals(segmentDefinition: String): List<Interval>? {
        val elements = segmentDefinition.split(",")

        val intervals = mutableListOf<Interval>()
        elements.forEach { el ->
            val range = el.trim().split("-").map { it.trim().toDoubleOrNull() ?: return null }
            when (range.size) {
                2 -> intervals.add(Interval(range[0], range[1]))
                else -> return null
            }
        }
        return intervals
    }

    private fun parsePointPairs(input: String) : List<Pair<Double, Double>>? {
        val data = input.split(",").map {
            it.replace("(", "").replace(")", "").trim().toDoubleOrNull() ?: return null
        }

        if (data.size % 2 == 1) return null

        return data.asSequence().windowed(2, 2).map {
            it[0] to it[1]
        }.toList()
    }

}