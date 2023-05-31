package org.megras.segmentation.type

import org.davidmoten.hilbert.HilbertCurve
import org.davidmoten.hilbert.SmallHilbertCurve
import org.megras.segmentation.Bounds
import org.megras.segmentation.SegmentationType
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.math.pow
import kotlin.math.roundToLong

class Hilbert(val order: Int, var intervals: List<Interval>) : Segmentation, PreprocessSegmentation {
    override val segmentationType: SegmentationType = SegmentationType.HILBERT
    override var bounds = Bounds()

    private lateinit var hilbertCurve: SmallHilbertCurve
    private val dimensionSize = (2.0).pow(order) - 1

    init {
        require(intervals.all { it.low <= it.high }) {
            throw IllegalArgumentException("Ranges are not valid.")
        }
    }

    override fun equivalentTo(rhs: Segmentation): Boolean = false

    override fun contains(rhs: Segmentation): Boolean = false

    override fun contains(rhs: Bounds): Boolean = true

    override fun preprocess(bounds: Bounds): Segmentation? {
        hilbertCurve = HilbertCurve.small().bits(order).dimensions(bounds.dimensions)

        require(intervals.all { it.high <= hilbertCurve.maxIndex() }) {
            throw IllegalArgumentException("Range is out of bounds.")
        }

        return if (bounds.hasX() && bounds.hasY()) {
            if (bounds.hasT()) {
                toRotoscope(bounds.getXDimension().toInt(), bounds.getYDimension().toInt(), bounds.getTDimension())
            } else {
                toImageMask(bounds.getXDimension().toInt(), bounds.getYDimension().toInt())
            }
        } else {
            null
        }
    }

    private fun toImageMask(width: Int, height: Int): ImageMask {
        val mask = BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY)
        for (x in 0 until width) {
            for (y in 0 until height) {
                if (isIncluded(x.toDouble() / width, y.toDouble() / height)) {
                    mask.setRGB(x, y, Color.WHITE.rgb)
                }
            }
        }
        return ImageMask(mask)
    }

    private fun toImageMask(width: Int, height: Int, relativeTime: Double): ImageMask {
        val mask = BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY)
        for (x in 0 until width) {
            for (y in 0 until height) {
                if (isIncluded(x.toDouble() / width, y.toDouble() / height, relativeTime)) {
                    mask.setRGB(x, y, Color.WHITE.rgb)
                }
            }
        }
        return ImageMask(mask)
    }

    private fun toRotoscope(width: Int, height: Int, duration: Double): Rotoscope {
        val rotoscopeList = mutableListOf<RotoscopePair>()

        val startTime = System.currentTimeMillis()

        for (i in 0 until dimensionSize.toInt()) {
            val mask = toImageMask(width, height, i / dimensionSize)
            rotoscopeList.add(RotoscopePair(duration * i / dimensionSize, mask))
            rotoscopeList.add(RotoscopePair(duration * (i + 0.999) / dimensionSize, mask))
        }
        val estimatedTime = System.currentTimeMillis() - startTime;
        println(estimatedTime / 1000)
        return Rotoscope(rotoscopeList)
    }

    private fun isIncluded(vararg relativeCoords: Double): Boolean {

        // Translate to hilbert space
        val hilbertCoords = relativeCoords.map { (it * dimensionSize).roundToLong() }.toMutableList()

        val hilbertIndex = hilbertCurve.index(*hilbertCoords.toLongArray())
        val found = intervals.find { i -> i.low <= hilbertIndex && hilbertIndex <= i.high }

        return found != null
    }

    override fun getDefinition(): String = "${order}," + intervals.joinToString(",") {"${it.low}-${it.high}"}
}