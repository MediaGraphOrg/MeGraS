package org.megras.util

import com.thebuzzmedia.exiftool.ExifToolBuilder
import com.thebuzzmedia.exiftool.Tag
import com.thebuzzmedia.exiftool.exceptions.ExifToolNotFoundException
import org.megras.data.fs.file.PseudoFile
import org.megras.data.graph.Quad
import org.megras.data.graph.StringValue
import org.megras.data.graph.URIValue
import org.megras.data.graph.LongValue
import org.megras.data.graph.DoubleValue
import org.megras.data.graph.DoubleVectorValue
import org.megras.data.graph.QuadValue
import org.megras.data.graph.TemporalValue
import java.io.File
import java.time.format.DateTimeParseException
import org.megras.graphstore.BasicMutableQuadSet
import org.megras.graphstore.QuadSet
import org.megras.id.ObjectId
import org.slf4j.LoggerFactory
import java.io.FileOutputStream
import java.io.IOException
import kotlin.io.use

object ExifUtil {
    private val logger = LoggerFactory.getLogger(this.javaClass)

    private fun parseValue(value: String): QuadValue? {
        val trimmed = value.trim()
        // Return null for invalid date string
        if (trimmed == "0000:00:00 00:00:00") {
            return null
        }
        // Try Vector: matches (a, b, c) or a b c or a, b, c
        val vectorRegex = Regex("^\\(? *-?\\d*\\.?\\d+( *[, ] *-?\\d*\\.?\\d+)* *\\)?$")
        if (vectorRegex.matches(trimmed)) {
            val vectorString = trimmed.removePrefix("(").removeSuffix(")")
            val parts = vectorString.split(',', ' ').mapNotNull { it.trim().toDoubleOrNull() }
            if (parts.size > 1) {
                return DoubleVectorValue(parts)
            }
        }
        // Try Long
        if (trimmed.matches(Regex("^-?\\d+$"))) {
            return LongValue(trimmed.toLong())
        }
        // Try Double
        if (trimmed.matches(Regex("^-?\\d*\\.\\d+$"))) {
            return DoubleValue(trimmed.toDouble())
        }
        // Try parsing as LocalDateTime
        try {
            return TemporalValue(trimmed)
        } catch (_: Exception) {}
        // Fallback to String
        return StringValue(trimmed)
    }

    fun getExifData(
        file: PseudoFile,
        oid: ObjectId
    ): QuadSet {
        val tempFile: File = try {
            File.createTempFile("temp-exif-${file.name}-", ".tmp")
        } catch (_: IOException) {
            logger.error("Failed to create temporary file.")
            return BasicMutableQuadSet()
        }

        try {
            // Copy the contents of the InputStream to the temporary file
            file.inputStream().use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            val quads = BasicMutableQuadSet()
            ExifToolBuilder().build().use { tool ->
                // Get all metadata for the specified file
                val metadata: Map<Tag, String> = tool.getImageMeta(tempFile)

                // Check if any metadata was found
                if (metadata.isEmpty()) {
                    logger.info("No metadata found for the file: ${tempFile.absolutePath}")
                } else {
                    quads.apply{
                        metadata.forEach {tag, value ->
                            if (value.isNotEmpty()) {
                                val parsedValue = parseValue(value)
                                if (parsedValue != null) {
                                    add(
                                        Quad(
                                            oid,
                                            URIValue(Constants.EXIF_PREFIX + "/" + tag.name),
                                            parsedValue
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
            return quads
        } catch (e: Exception) {
            when (e) {
                is IOException -> logger.error("Error reading EXIF data from file: ${file.name}, ${e.message}")
                is ExifToolNotFoundException -> logger.error("ExifTool not found. Please ensure it is installed and in your PATH.")
                else -> logger.error("Unexpected error: ${e.message}")
            }
            return BasicMutableQuadSet()
        }
    }
}