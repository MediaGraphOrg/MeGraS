package org.megras.segmentation.media

import com.github.kokorin.jaffree.StreamType
import com.github.kokorin.jaffree.ffmpeg.*
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.megras.api.rest.RestErrorStatus
import org.megras.segmentation.type.*
import org.slf4j.LoggerFactory
import java.nio.channels.SeekableByteChannel
import java.util.*
import java.util.concurrent.TimeUnit


/**
 * For more information, see the Jaffree mosaic example
 * https://github.com/kokorin/Jaffree/blob/master/src/test/java/examples/MosaicExample.java
 */
class VideoShapeSegmenter(
    private val videoStream: SeekableByteChannel,
    val segmentation: Segmentation,
    val frameRate: Int,
    val width: Int,
    val height: Int
) {

    fun execute(): ByteArray {
        val out = SeekableInMemoryByteChannel()

        val frameIterator = FrameIterator()

        val ffmpegThread = Thread({
            FFmpeg.atPath()
                .addInput(ChannelInput.fromChannel(videoStream))
                .addOutput(
                    FrameOutput.withConsumerAlpha(frameIterator.consumer)
                        .setFrameRate(frameRate)
                )
                .setContextName("input")
                .execute()
        }, "Reader-main")

        ffmpegThread.isDaemon = true
        ffmpegThread.start()

        val xBounds = segmentation.bounds.getXBounds()
        val yBounds = segmentation.bounds.getYBounds()
        val tBounds = if(segmentation.bounds.hasT()) {segmentation.bounds.getTBounds()} else {null}

        val frameWidth = if (xBounds[0].isNaN()) {width} else {(xBounds[1] - xBounds[0]).toInt()}
        val frameHeight = if (yBounds[0].isNaN()) {height} else {(yBounds[1] - yBounds[0]).toInt()}

        val frameProducer = createFrameProducer(frameIterator, tBounds?.get(0) ?: 0.0, frameWidth, frameHeight)

        val ffmpeg = FFmpeg.atPath()
            .addInput(
                FrameInput.withProducer(frameProducer, ImageFormats.ABGR, 5000L)
                    .setFrameRate(frameRate)
            )
        // Optionally add temporal filter
        if (tBounds != null) {
            ffmpeg.addInput(
                ChannelInput.fromChannel(videoStream)
                    .setPosition(tBounds[0], TimeUnit.MILLISECONDS)
                    .setDuration(tBounds[1] - tBounds[0], TimeUnit.MILLISECONDS)
            )
        } else {
            ffmpeg.addInput(ChannelInput.fromChannel(videoStream))
        }
        // Optionally crop frames
        ffmpeg
            .setOverwriteOutput(true)
            .addOutput(
                ChannelOutput.toChannel("", out)
                .setFormat("webm")
                .addMap(0, StreamType.VIDEO)
                .addMap(1, StreamType.AUDIO)
            )
            .setContextName("output")
            .execute()

        return out.array()
    }

    private fun createFrameProducer(frameIterator: FrameIterator, shift: Double, width: Int, height: Int): FrameProducer {
        return object : FrameProducer {
            private val shiftTimecode = shift.toLong()
            private val videoFrameDuration = (1000 / frameRate).toLong()
            private var timecode: Long = shiftTimecode
            private var nextVideoFrame: Frame? = null
            private var nextVideoFrameTimecode: Long = shiftTimecode

            override fun produceStreams(): MutableList<Stream> {
                val streams: MutableList<Stream> = ArrayList()
                streams.add(
                    Stream()
                        .setId(0)
                        .setType(Stream.Type.VIDEO)
                        .setTimebase(1000L)
                        .setWidth(width)
                        .setHeight(height)
                )

                return streams
            }

            override fun produce(): Frame? {
                var result: Frame? = null
                if (nextVideoFrameTimecode <= timecode) {
                    if (nextVideoFrameTimecode == shiftTimecode) {
                        readNextVideoFrames(nextVideoFrameTimecode)
                    }
                    result = produceVideoFrame(nextVideoFrame)
                    readNextVideoFrames(nextVideoFrameTimecode)
                }
                timecode = nextVideoFrameTimecode
                return result
            }

            fun produceVideoFrame(videoFrame: Frame?): Frame? {
                if (videoFrame == null) {
                    return null
                }

                val seg = when (segmentation) {
                    is Rotoscope -> segmentation.slice(nextVideoFrameTimecode.toDouble())
                    is MeshBody -> segmentation.slice(nextVideoFrameTimecode.toDouble())
                    else -> segmentation
                } ?: return null


                val segmentedImage = ImageSegmenter.segment(videoFrame.image, seg) ?: throw RestErrorStatus.invalidSegmentation
                val result: Frame = Frame.createVideoFrame(0, nextVideoFrameTimecode, segmentedImage)
                nextVideoFrameTimecode += videoFrameDuration
                return result
            }

            private fun readNextVideoFrames(videoTs: Long) {
                if (!frameIterator.hasNext) {
                    nextVideoFrame = null
                }
                while (frameIterator.hasNext) {
                    val frame: Frame = frameIterator.next() ?: break
                    val stream = frameIterator.getStream(frame.streamId) ?: break
                    when (stream.type) {
                        Stream.Type.VIDEO -> nextVideoFrame = frame
                        else -> {}
                    }
                    val frameTs: Long = 1000L * frame.pts / stream.timebase
                    if (frameTs >= videoTs) {
                        break
                    }
                }
            }
        }
    }

    class FrameIterator : MutableIterator<Frame?> {

        private val logger = LoggerFactory.getLogger(this.javaClass)

        @Volatile
        var hasNext = true

        @Volatile
        private var next: Frame? = null

        @Volatile
        var tracks: List<Stream>? = null
            private set
        val consumer: FrameConsumer = object : FrameConsumer {
            override fun consumeStreams(tracks: MutableList<Stream>) {
                this@FrameIterator.tracks = tracks
            }

            override fun consume(frame: Frame?) {
                while (next != null) {
                    try {
                        Thread.sleep(10)
                    } catch (e: InterruptedException) {
                        logger.warn("Exception while supplying frame", e)
                    }
                }
                hasNext = frame != null
                next = frame
            }
        }

        override fun hasNext(): Boolean {
            waitForNextFrame()
            return hasNext
        }

        override fun next(): Frame? {
            waitForNextFrame()
            val result: Frame? = next
            next = null
            return result
        }

        override fun remove() {
            throw UnsupportedOperationException("remove")
        }

        fun getStream(id: Int): Stream? {
            for (stream in tracks!!) {
                if (stream.id == id) {
                    return stream
                }
            }
            return null
        }

        private fun waitForNextFrame() {
            while (hasNext && next == null) {
                try {
                    Thread.sleep(10)
                } catch (e: InterruptedException) {
                    logger.warn("Exception while waiting for frame", e)
                }
            }
        }
    }
}