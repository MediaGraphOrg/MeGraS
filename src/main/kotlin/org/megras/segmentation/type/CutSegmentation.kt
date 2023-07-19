package org.megras.segmentation.type

import com.ezylang.evalex.Expression
import org.megras.segmentation.Bounds
import org.megras.segmentation.SegmentationType
import org.megras.util.ObjUtil
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.math.BigDecimal
import javax.imageio.ImageIO

abstract class CutSegmentation : Segmentation, PreprocessSegmentation {
    override val segmentationType = SegmentationType.CUT
    override var bounds: Bounds = Bounds()

    abstract val expression: Expression
    abstract val above: Boolean

    override fun equivalentTo(rhs: Segmentation): Boolean {
        return rhs is CutSegmentation &&
                this.expression == rhs.expression && this.above == rhs.above
    }

    override fun contains(rhs: Segmentation): Boolean = false

    override fun contains(rhs: Bounds): Boolean = true

    override fun orthogonalTo(rhs: Segmentation): Boolean {
        return rhs !is CutSegmentation
    }

    override fun preprocess(bounds: Bounds): Segmentation? =
        if (bounds.hasX() && bounds.hasY()) {
            if (bounds.hasT() || bounds.hasZ()) {
                toMeshBody(bounds)
            } else {
                toShape(bounds)
            }
        } else {
            null
        }

    abstract fun toShape(bounds: Bounds): TwoDimensionalSegmentation

    private fun toMeshBody(bounds: Bounds): ThreeDimensionalSegmentation {
        val z = if (bounds.hasZ()) {
            bounds.getZBounds()
        } else {
            bounds.getTBounds()
        }
        val obj = ObjUtil.generateCuboid(
            bounds.getMinX().toFloat(), bounds.getMaxX().toFloat(),
            bounds.getMinY().toFloat(), bounds.getMaxY().toFloat(),
            z[0].toFloat(), z[1].toFloat()
        )
        val segmentedObj = ObjUtil.segmentCut(obj, expression, above)
        return MeshBody(segmentedObj)
    }

    override fun getDefinition(): String = expression.expressionString + if (above) ",above" else ",below"
}

class LinearCutSegmentation(
    private val a: Double,
    private val b: Double,
    private val c: Double,
    private val d: Double,
    override val above: Boolean
) : CutSegmentation() {
    override val expression = if (d == 0.0) {
        Expression("${a}*x + ${b}*y + $c")
    } else {
        Expression("${a}*x + ${b}*y + ${c}*z + $d")
    }

    override fun toShape(bounds: Bounds): TwoDimensionalSegmentation {
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
}

class GeneralCutSegmentation(override val expression: Expression, override val above: Boolean) : CutSegmentation() {
    override fun toShape(bounds: Bounds): TwoDimensionalSegmentation {
        val mask = BufferedImage(
            bounds.getXDimension().toInt(),
            bounds.getYDimension().toInt(),
            BufferedImage.TYPE_BYTE_BINARY
        )

        for (x in 0 until mask.width) {
            for (y in 0 until mask.height) {
                val res = expression.with("x", x).with("y", y).evaluate()
                val positive = res.numberValue >= BigDecimal.ZERO
                if (positive == above) {
                    mask.setRGB(x, mask.height - 1 - y, Color.WHITE.rgb)
                } else {
                    mask.setRGB(x, mask.height - 1 - y, Color.BLACK.rgb)
                }
            }
        }
        ImageIO.write(mask, "png", File("test.png"))
        return ImageMask(mask)
    }
}
