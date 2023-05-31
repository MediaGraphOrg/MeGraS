package org.megras.segmentation.type

import org.megras.segmentation.Bounds
import org.megras.segmentation.SegmentationType
import org.megras.util.ObjUtil

class SliceSegmentation(val a: Double, val b: Double, val c: Double, val d: Double, val above: Boolean) : Segmentation, PreprocessSegmentation {
    override val segmentationType = SegmentationType.SLICE
    override var bounds: Bounds = Bounds()

    override fun equivalentTo(rhs: Segmentation): Boolean {
        return rhs is SliceSegmentation &&
                this.a == rhs.a && this.b == rhs.b && this.c == rhs.c && this.d == rhs.d && this.above == rhs.above
    }

    override fun contains(rhs: Segmentation): Boolean {
        if (rhs !is SliceSegmentation) return false
        return this.a == rhs.a && this.b == rhs.b && this.c == rhs.c && this.above == rhs.above &&
                ((above && rhs.d <= this.d) || (!above && this.d <= rhs.d))
    }

    override fun contains(rhs: Bounds): Boolean = true

    override fun orthogonalTo(rhs: Segmentation): Boolean {
        return rhs !is SliceSegmentation
    }

    override fun preprocess(bounds: Bounds): Segmentation? =
        when (bounds.dimensions) {
            2 -> toShape(bounds)
            3 -> toMeshBody(bounds)
            else -> null
        }

    private fun toShape(bounds: Bounds): TwoDimensionalSegmentation {
        val minX = bounds.getMinX()
        val maxX = bounds.getXDimension() + minX
        val minY = bounds.getMinY()
        val maxY = bounds.getYDimension() + minY

        // collect all corners and intersection points
        val points: MutableList<Pair<Double, Double>> = ArrayList()
        points.add(minX to minY)

        var intersection = computeIntersectionPoint(minX, minY, minX, maxY) // left side
        if (intersection != null) points.add(intersection)
        points.add(minX to maxY)

        intersection = computeIntersectionPoint(minX, maxY, maxX, maxY) // top side
        if (intersection != null) points.add(intersection)
        points.add(maxX to maxY)

        intersection = computeIntersectionPoint(maxX, minY, maxX, maxY) // right side
        if (intersection != null) points.add(intersection)
        points.add(maxX to minY)

        intersection = computeIntersectionPoint(minX, minY, maxX, minY) // bottom side
        if (intersection != null) points.add(intersection)

        // remove the points above/below the line
        if (above) {
            points.removeIf { a * it.first + b * it.second - c < 0 }
        } else {
            points.removeIf { a * it.first + b * it.second - c > 0 }
        }
        return Polygon(points.distinct())
    }

    private fun computeIntersectionPoint(x1: Double, y1: Double, x2: Double, y2: Double): Pair<Double, Double>? {
        val a2 = y2 - y1
        val b2 = x1 - x2
        val d2 = a2 * x1 + b2 * y1
        val determinant = a * b2 - a2 * b
        if (determinant == 0.0) return null

        var x = (b2 * d - b * d2) / determinant
        if (x == -0.0) x = 0.0
        var y = (a * d2 - a2 * d) / determinant
        if (y == -0.0) y = 0.0

        if ((x > x1 && x > x2) || (x < x1 && x < x2) || (y > y1 && y > y2) || (y < y1 && y < y2)) return null

        return x to y
    }

    private fun toMeshBody(bounds: Bounds): ThreeDimensionalSegmentation {
        val obj = ObjUtil.generateCuboid(
            bounds.getMinX().toFloat(), bounds.getMaxX().toFloat(),
            bounds.getMinY().toFloat(), bounds.getMaxY().toFloat(),
            bounds.getMinT().toFloat(), bounds.getMaxT().toFloat()
        )
        val segmentedObj = ObjUtil.segmentSlice(obj, a, b, c, d, above)
        return MeshBody(segmentedObj)
    }

    override fun getDefinition(): String = "$a,$b,$c,$d," + if (above) "1" else "0"
}