package org.megras.segmentation.type

import de.javagl.obj.Obj
import de.javagl.obj.ObjWriter
import de.sciss.shapeint.ShapeInterpolator
import org.megras.segmentation.Bounds
import org.megras.segmentation.SegmentationType
import org.megras.util.ObjUtil
import java.awt.Shape
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

abstract class ThreeDimensionalSegmentation : Segmentation {
    override fun equivalentTo(rhs: Segmentation): Boolean {
        if (rhs !is ThreeDimensionalSegmentation) return false
        if (this.bounds != rhs.bounds) return false

        val timeBounds = this.bounds.getTBounds()
        for (t in timeBounds[0].toInt() .. timeBounds[0].toInt()) {
            val shape1 = this.slice(t)
            val shape2 = rhs.slice(t)
            if (shape1 != null && shape2 != null && !shape1.equivalentTo(shape2)) return false
        }
        return true
    }

    override fun contains(rhs: Segmentation): Boolean {
        if (rhs !is ThreeDimensionalSegmentation) return false
        if (!this.bounds.contains(rhs.bounds)) return false

        val timeBounds = rhs.bounds.getTBounds()
        for (t in timeBounds[0].toInt() .. timeBounds[1].toInt()) {
            val shape1 = this.slice(t)
            val shape2 = rhs.slice(t)
            if (shape1 != null && shape2 != null && !shape1.contains(shape2)) return false
        }
        return true
    }

    abstract fun slice(time: Double): TwoDimensionalSegmentation?

    fun slice(time: Int): TwoDimensionalSegmentation? = slice(time.toDouble())
}

data class RotoscopePair(var time: Double, var space: TwoDimensionalSegmentation)

class Rotoscope(var rotoscopeList: List<RotoscopePair>) : ThreeDimensionalSegmentation(), RelativeSegmentation {
    override val segmentationType = SegmentationType.ROTOSCOPE
    override lateinit var bounds: Bounds

    override val isRelative = rotoscopeList.all { it.time in 0.0 .. 1.0 && (it.space is RelativeSegmentation && (it.space as RelativeSegmentation).isRelative) }

    init {
        require(rotoscopeList.size >= 2) {
            throw IllegalArgumentException("Need at least two transition points.")
        }

        val timePoints = rotoscopeList.map { it.time }
        val sortedTimePoints = timePoints.sorted()
        require(timePoints == sortedTimePoints) {
            throw IllegalArgumentException("Need input sorted by increasing time points")
        }

        var minX = Double.MAX_VALUE
        var maxX = Double.MIN_VALUE
        var minY = Double.MAX_VALUE
        var maxY = Double.MIN_VALUE
        rotoscopeList.forEach { i ->
            val bounds = i.space.shape.bounds
            if (bounds.minX < minX) minX = bounds.minX
            if (bounds.maxX > maxX) maxX = bounds.maxX
            if (bounds.minY < minY) minY = bounds.minY
            if (bounds.maxY > maxY) maxY = bounds.maxY
        }
        bounds = Bounds()
            .addX(minX, maxX)
            .addY(minY, maxY)
            .addT(rotoscopeList.first().time, rotoscopeList.last().time)
    }

    override fun contains(rhs: Bounds): Boolean {
        val minT = rhs.getMinT()
        val maxT = rhs.getMaxT()
        if (minT.isNaN() || maxT.isNaN()) return false

        return this.rotoscopeList.first().time <= minT && this.rotoscopeList.last().time >= maxT && this.rotoscopeList.all { it.space.contains(rhs) }
    }

    override fun translate(by: Bounds): Segmentation {
        when (by.dimensions) {
            2 -> return Rotoscope(rotoscopeList.map { RotoscopePair(it.time, it.space.translate(by) as TwoDimensionalSegmentation) })
            3 -> return Rotoscope(rotoscopeList.map { RotoscopePair(it.time + by.getMinT(), it.space.translate(by) as TwoDimensionalSegmentation) })
        }
        return this
    }

    override fun slice(time: Double): TwoDimensionalSegmentation? {
        if (time < rotoscopeList.first().time || time > rotoscopeList.last().time) return null

        var endIndex = rotoscopeList.indexOfFirst { it.time > time }
        if (endIndex == -1 && rotoscopeList.last().time == time) {
            endIndex = rotoscopeList.size - 1
        }
        val (endFrame, endShape) = rotoscopeList[endIndex]

        val startIndex = endIndex - 1
        val (startFrame, startShape) = rotoscopeList[startIndex]

        val t = (time - startFrame) / (endFrame - startFrame)
        val newShape = ShapeInterpolator().evaluate(startShape.shape, endShape.shape, t.toFloat())

        val newBounds = bounds
        return object: TwoDimensionalSegmentation() {
            override val segmentationType = null
            override var shape: Shape = newShape
            override var bounds: Bounds = newBounds
            override fun getDefinition(): String = ""
        }
    }

    override fun toAbsolute(bounds: Bounds): Segmentation? {
        if (bounds.dimensions < 3) return null
        val tFactor = bounds.getTDimension()
        return Rotoscope(rotoscopeList.map {
            val shape = it.space as RelativeSegmentation
            RotoscopePair(it.time * tFactor, shape.toAbsolute(bounds) as TwoDimensionalSegmentation)
        })
        // TODO: handle case where only time or space is relative
    }

    override fun getDefinition(): String = rotoscopeList.joinToString(";") {
        val shapeString = it.space.toURI().removePrefix("segment/").replace("/", ",")
        "${it.time},${shapeString}"
    }
}

class MeshBody(private var obj: Obj) : ThreeDimensionalSegmentation(), RelativeSegmentation {
    override val segmentationType = SegmentationType.MESH
    override lateinit var bounds: Bounds
    override var isRelative = false

    init {
        obj = ObjUtil.sortMesh(obj)
        bounds = ObjUtil.computeBounds(obj)
        isRelative = bounds.isRelative()
    }

    override fun contains(rhs: Bounds): Boolean {
        val minX = rhs.getMinX()
        val maxX = rhs.getMaxX()
        val minY = rhs.getMinY()
        val maxY = rhs.getMaxY()
        val minT = rhs.getMinT()
        val maxT = rhs.getMaxT()
        if (minX.isNaN() || maxX.isNaN() || minY.isNaN() || maxY.isNaN() || minT.isNaN() || maxT.isNaN()) return false

        // TODO
        return false
    }

    override fun translate(by: Bounds): Segmentation {
        val minX = by.getMinX().toFloat()
        val minY = by.getMinY().toFloat()
        val minT = by.getMinT().toFloat()

        val translatedObj = ObjUtil.translate(obj, minX, minY, minT)
        return MeshBody(translatedObj)
    }

    override fun slice(time: Double): TwoDimensionalSegmentation? {
        val z = time.toFloat()
        val path = ObjUtil.slice(obj, z) ?: return null

        val newBounds = bounds
        return object: TwoDimensionalSegmentation() {
            override var shape: Shape = path
            override var bounds: Bounds = newBounds
            override val segmentationType = null
            override fun getDefinition(): String = ""
        }
    }

    override fun toAbsolute(bounds: Bounds): Segmentation? {
        if (bounds.dimensions < 3) return null
        val xFactor = bounds.getXDimension().toFloat()
        val yFactor = bounds.getYDimension().toFloat()
        val tFactor = bounds.getTDimension().toFloat()

        val newObj = ObjUtil.scale(obj, xFactor, yFactor, tFactor)
        return MeshBody(newObj)
    }

    override fun getDefinition(): String {
        val outputStream = ByteArrayOutputStream()
        ObjWriter.write(obj, outputStream)
        val outputString = outputStream.toString(Charset.defaultCharset())
        return outputString.replace("\n", ",")
    }
}