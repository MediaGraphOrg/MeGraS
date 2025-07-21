package org.megras.util

import com.drew.imaging.ImageMetadataReader
import org.megras.data.fs.file.PseudoFile
import org.megras.data.graph.Quad
import org.megras.data.graph.StringValue
import org.megras.data.graph.URIValue
import org.megras.graphstore.BasicMutableQuadSet
import org.megras.graphstore.QuadSet
import org.megras.id.ObjectId

object ExifUtil {
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
                            StringValue(tag.description.trim())
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            //logger.warn("Could not read EXIF data from file ${file.name}: ${e.localizedMessage}")
            BasicMutableQuadSet()
        }
    }
}