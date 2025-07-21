package org.megras.util

import com.drew.imaging.ImageMetadataReader
import org.megras.data.fs.file.PseudoFile
import org.megras.data.graph.Quad
import org.megras.data.graph.StringValue
import org.megras.data.graph.URIValue
import org.megras.data.graph.LongValue
import org.megras.data.graph.DoubleValue
import org.megras.data.graph.DoubleVectorValue
import org.megras.data.graph.QuadValue
import org.megras.data.graph.TemporalValue
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import org.megras.graphstore.BasicMutableQuadSet
import org.megras.graphstore.QuadSet
import org.megras.id.ObjectId

object ExifUtil {
    private val dateTimePatterns = listOf(
        "yyyy:MM:dd HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy/MM/dd HH:mm:ss"
    )

    private fun parseValue(description: String): QuadValue {
        val trimmed = description.trim()
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
        // Try DateTime
        for (pattern in dateTimePatterns) {
            try {
                LocalDateTime.parse(trimmed, DateTimeFormatter.ofPattern(pattern))
                return TemporalValue(trimmed)
            } catch (_: DateTimeParseException) {}
        }
        // Fallback to String
        return StringValue(trimmed)
    }

    fun getExifData(
        file: PseudoFile,
        oid: ObjectId
    ): QuadSet {
        return try {
            val metadata = ImageMetadataReader.readMetadata(file.inputStream())
            BasicMutableQuadSet().apply {
                metadata.directories.forEach { directory ->
                    directory.tags.forEach { tag ->
                        add(Quad(
                            oid,
                            URIValue(Constants.EXIF_PREFIX + "/" + tag.tagName.replace(" ", "")),
                            parseValue(tag.description)
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            println("Error reading EXIF data for file '${file.name}': ${e.message}")
            BasicMutableQuadSet()
        }
    }
}