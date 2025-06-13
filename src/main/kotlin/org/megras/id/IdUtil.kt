package org.megras.id


import org.megras.data.fs.file.PseudoFile
import org.megras.data.model.MediaType
import org.megras.util.HashUtil
import org.megras.util.extensions.toBase64


object IdUtil {

    fun getMediaType(id: String): MediaType = if (id.isBlank()) {
        MediaType.UNKNOWN
    } else {
        MediaType.prefixMap[id[0]] ?: MediaType.UNKNOWN
    }

    fun generateId(file: PseudoFile): ObjectId {
        return ObjectId(HashUtil.hash(file.inputStream()).toBase64().trim()
        )
    }

}