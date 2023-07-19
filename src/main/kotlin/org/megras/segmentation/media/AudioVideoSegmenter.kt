package org.megras.segmentation.media

import com.github.kokorin.jaffree.StreamType
import com.github.kokorin.jaffree.ffmpeg.*
import com.github.kokorin.jaffree.ffprobe.FFprobe
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.megras.api.rest.RestErrorStatus
import org.megras.data.fs.ObjectStoreResult
import org.megras.segmentation.Bounds
import org.megras.segmentation.type.*
import org.slf4j.LoggerFactory
import java.nio.channels.SeekableByteChannel
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong


object AudioVideoSegmenter {

    private const val outputFormat = "webm"

    private val logger = LoggerFactory.getLogger(this.javaClass)

    fun segment(storedObject: ObjectStoreResult, segmentation: Segmentation): SegmentationResult? = try {
        when (segmentation) {
            is Rect -> segmentRect(storedObject, segmentation)
            is TwoDimensionalSegmentation,
            is ThreeDimensionalSegmentation,
            is ColorChannel -> segmentPerFrame(storedObject, segmentation)
            is StreamChannel -> segmentChannel(storedObject, segmentation)
            is Time -> segmentTime(storedObject, segmentation)
            is Frequency -> segmentFrequency(storedObject, segmentation)
            else -> {
                logger.warn("Segmentation type '${segmentation.getType()}' not applicable to audio/video")
                null
            }
        }
    } catch (e: Exception) {
        logger.error("Error while segmenting audio/video: ${e.localizedMessage}")
        null
    }

    private fun segmentRect(storedObject: ObjectStoreResult, rect: Rect): SegmentationResult {
        val stream = storedObject.byteChannel()
        if (!hasStreamType(stream, StreamType.VIDEO)) {
            throw RestErrorStatus.noVideo
        }

        val out = SeekableInMemoryByteChannel()
        FFmpeg.atPath()
            .addInput(ChannelInput.fromChannel(stream))
            .setFilter(StreamType.VIDEO, "crop=w=${rect.width}:h=${rect.height}:x=${rect.xmin}:y=${rect.ymin}")
            .setOverwriteOutput(true)
            .addOutput(ChannelOutput.toChannel("", out).setFormat(outputFormat))
            .execute()
        return SegmentationResult(out.array(),
            Bounds().addX(0, rect.width).addY(0, rect.height)
                .addT(storedObject.descriptor.bounds.getMinT(), storedObject.descriptor.bounds.getMaxT())
        )
    }

    private fun segmentPerFrame(storedObject: ObjectStoreResult, segmentation: Segmentation): SegmentationResult {
        val stream = storedObject.byteChannel()
        if (!hasStreamType(stream, StreamType.VIDEO)) {
            throw RestErrorStatus.noVideo
        }

        val probe = probeStream(stream)
        val videoProbe = probe.first { s -> s.codecType == StreamType.VIDEO }
        val frameRate = videoProbe.rFrameRate.toInt()

        val totalDuration = AtomicLong()
        FFmpeg.atPath()
            .addInput(ChannelInput.fromChannel(stream))
            .addOutput(NullOutput())
            .setProgressListener { progress -> totalDuration.set(progress.timeMillis) }
            .execute()

        val segment = VideoShapeSegmenter(
            stream,
            segmentation,
            frameRate,
            videoProbe.width,
            videoProbe.height
        ).execute()

        val width = if (segmentation.bounds.hasX()) {
            segmentation.bounds.getXDimension()
        } else {
            videoProbe.width
        }
        val height = if (segmentation.bounds.hasY()) {
            segmentation.bounds.getYDimension()
        } else {
            videoProbe.height
        }
        val duration = if (segmentation.bounds.hasT()) {
            segmentation.bounds.getTDimension()
        } else {
            totalDuration.get()
        }

        return SegmentationResult(segment, Bounds().addX(0, width).addY(0, height).addT(0, duration))
    }

    /**
     * 2 possible cases:
     * - selection is either "audio" or "video", then discard the other
     * - selection is indices of streams
     */
    private fun segmentChannel(storedObject: ObjectStoreResult, channel: StreamChannel): SegmentationResult? {
        val stream = storedObject.byteChannel()
        val out = SeekableInMemoryByteChannel()
        val ffmpeg = FFmpeg
            .atPath()
            .addInput(ChannelInput.fromChannel(stream))
            .setOverwriteOutput(true)

        // 'audio' or 'video' stream selection
        if (channel.selection.size == 1 && (channel.selection[0] == "audio" || channel.selection[0] == "video")) {
            if (channel.selection[0] == "video" && !hasStreamType(stream, StreamType.VIDEO)) {
                throw RestErrorStatus.noVideo
            }
            if (channel.selection[0] == "audio" && !hasStreamType(stream, StreamType.AUDIO)) {
                throw RestErrorStatus.noAudio
            }

            when(channel.selection[0]) {
                "video" -> {
                    ffmpeg.addArguments("-c:v", "copy").addArgument("-an")
                }

                "audio" -> {
                    ffmpeg.addArguments("-c:a", "copy").addArgument("-vn")
                }
            }
        }
        // stream number selection
        else if (channel.selection.all { it.toIntOrNull() != null }){
            channel.selection.forEach { ffmpeg.addArguments("-map", "0:${it}") }
            ffmpeg.addArguments("-c", "copy")
        } else {
            return null
        }

        ffmpeg.addOutput(ChannelOutput.toChannel("", out).setFormat(outputFormat)).execute()

        val bounds = Bounds()
        val probe = probeStream(out)
        val videoProbe = probe.firstOrNull { s -> s.codecType == StreamType.VIDEO }
        if (videoProbe != null) {
            bounds.addX(0, videoProbe.width).addY(0, videoProbe.height)
        }
        bounds.addT(0, storedObject.descriptor.bounds.getTDimension())

        return SegmentationResult(out.array(), bounds)
    }

    private fun segmentFrequency(storedObject: ObjectStoreResult, frequency: Frequency): SegmentationResult {
        val stream = storedObject.byteChannel()
        if (!hasStreamType(stream, StreamType.AUDIO)) {
            throw RestErrorStatus.noAudio
        }

        val out = SeekableInMemoryByteChannel()
        FFmpeg.atPath()
            .addInput(ChannelInput.fromChannel(stream))
            .setOverwriteOutput(true)
            .addArguments("-filter:a", "highpass=f=${frequency.interval.low}, lowpass=f=${frequency.interval.high}")
            .addOutput(ChannelOutput.toChannel("", out).setFormat(outputFormat))
            .execute()

        return SegmentationResult(out.array(), storedObject.descriptor.bounds)
    }

    private fun segmentTime(storedObject: ObjectStoreResult, time: Time): SegmentationResult {
        val stream = storedObject.byteChannel()
        val out = SeekableInMemoryByteChannel()

        val firstPoint = time.intervals.first().low
        val lastPoint = time.intervals.last().high

        val ffmpeg = FFmpeg.atPath()
                .addInput(ChannelInput.fromChannel(stream).setPosition(firstPoint, TimeUnit.MILLISECONDS)
                        .setDuration(lastPoint - firstPoint, TimeUnit.MILLISECONDS))
                .setOverwriteOutput(true)

        if (time.intervals.size > 1) {
            val discard = time.getIntervalsToDiscard()

            // The video between segments is turned black (need shifting because beginning might be cut away)
            // TODO: figure out how to make it transparent instead of black
            val blackFilters = discard.map { "drawbox=t=fill:c=black:enable='between(t,${it.low - firstPoint},${it.high - firstPoint})'" }
            ffmpeg.addArguments("-vf", blackFilters.joinToString(", "))

            // The audio between segments is muted (need shifting because beginning might be cut away)
            val muteFilters = discard.map { "volume=enable='between(t,${it.low - firstPoint},${it.high - firstPoint})':volume=0" }
            ffmpeg.addArguments("-af", muteFilters.joinToString(", "))
        }

        ffmpeg.addOutput(ChannelOutput.toChannel("", out).setFormat(outputFormat))
                .execute()

        val bounds = storedObject.descriptor.bounds
        bounds.addT(0, time.bounds.getTDimension())
        return SegmentationResult(out.array(), bounds)
    }

    private fun probeStream(stream: SeekableByteChannel): List<com.github.kokorin.jaffree.ffprobe.Stream> {
        val probe = FFprobe.atPath().setShowStreams(true).setInput(stream).execute()
        return probe.streams
    }

    private fun hasStreamType(stream: SeekableByteChannel, type: StreamType): Boolean {
        return probeStream(stream).find { it.codecType == type } != null
    }
}