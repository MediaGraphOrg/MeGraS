package org.megras.segmentation.media

import org.megras.segmentation.Bounds
import org.megras.segmentation.type.*
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.imageio.ImageIO


object ImageSegmenter {

    fun segment(inputStream: InputStream, segmentation: Segmentation, imageType: Int = BufferedImage.TYPE_4BYTE_ABGR): SegmentationResult? {
        val image = ImageIO.read(inputStream)
        val segmentedImage = segment(image, segmentation, imageType) ?: return null

        val outputStream = ByteArrayOutputStream()
        ImageIO.write(segmentedImage, "PNG", outputStream)

        return SegmentationResult(outputStream.toByteArray(), Bounds().addX(0, segmentedImage.width).addY(0, segmentedImage.height))
    }

    fun segment(image: BufferedImage, segmentation: Segmentation, imageType: Int = BufferedImage.TYPE_4BYTE_ABGR): BufferedImage? =
        when (segmentation) {
            is Width -> segmentWidth(image, segmentation, imageType)
            is Height -> segmentHeight(image, segmentation, imageType)
            is TwoDimensionalSegmentation -> segmentShape(image, segmentation, imageType)
            is ColorChannel -> segmentColor(image, segmentation, imageType)
            else -> null
        }

    private fun segmentWidth(image: BufferedImage, segmentation: Width, imageType: Int): BufferedImage? {
        return try {
            val xBounds = segmentation.bounds.getXBounds()

            val out = BufferedImage((xBounds[1] - xBounds[0]).toInt(), image.height, imageType)
            val g = out.createGraphics()
            g.drawImage(image, -xBounds[0].toInt(), 0, null)
            g.composite = AlphaComposite.Clear
            segmentation.getIntervalsToDiscard().forEach {
                g.fillRect((it.low - xBounds[0]).toInt(), 0, (it.high - it.low).toInt(), image.height)
            }
            g.dispose()

            out
        } catch (e: Exception) {
            //TODO log
            null
        }
    }

    private fun segmentHeight(image: BufferedImage, segmentation: Height, imageType: Int): BufferedImage? {
        return try {
            var yBounds = segmentation.bounds.getYBounds()
            // Recompute bounds to have the origin bottom left instead of top left
            yBounds = doubleArrayOf(image.height - yBounds[1], image.height - yBounds[0])

            val out = BufferedImage(image.width, (yBounds[1] - yBounds[0]).toInt(), imageType)
            val g = out.createGraphics()
            g.drawImage(image, 0, -yBounds[0].toInt(), null)
            g.composite = AlphaComposite.Clear
            segmentation.getIntervalsToDiscard().forEach {
                g.fillRect(0, (image.height - it.high - yBounds[0]).toInt(), image.width, (it.high - it.low).toInt())
            }
            g.dispose()

            out
        } catch (e: Exception) {
            //TODO log
            null
        }
    }

    private fun segmentShape(image: BufferedImage, segmentation: TwoDimensionalSegmentation, imageType: Int): BufferedImage? {
        return try {
            val xBounds = segmentation.bounds.getXBounds()
            val yBounds = segmentation.bounds.getYBounds()

            val transform = AffineTransform()
            transform.translate(-xBounds[0], -yBounds[0])
            transform.scale(1.0, -1.0)
            transform.translate(0.0, -yBounds[1] - yBounds[0])
            val movedShape = transform.createTransformedShape(segmentation.shape)

            val out = BufferedImage((xBounds[1] - xBounds[0]).toInt(), (yBounds[1] - yBounds[0]).toInt(), imageType)
            val g = out.createGraphics()
            g.clip(movedShape)
            g.drawImage(image, -xBounds[0].toInt(), -(image.height - yBounds[1]).toInt(), null)
            g.dispose()

            out
        } catch (e: Exception) {
            //TODO log
            null
        }
    }

    private fun segmentColor(image: BufferedImage, colorChannel: ColorChannel, imageType: Int): BufferedImage {
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                val color = Color(image.getRGB(x, y))
                var red = color.red
                var green = color.green
                var blue = color.blue

                if (!colorChannel.selection.contains("red")) red = 0
                if (!colorChannel.selection.contains("green")) green = 0
                if (!colorChannel.selection.contains("blue")) blue = 0

                image.setRGB(x, y, Color(red, green, blue, color.alpha).rgb)
            }
        }

        return if (image.type != imageType) {
            val out = BufferedImage(image.width, image.height, imageType)
            val g = out.createGraphics()
            g.drawImage(image, 0, 0, null)
            g.dispose()
            out
        } else {
            image
        }
    }
}