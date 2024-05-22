package org.megras.data.fs

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption

data class ObjectStoreResult(val descriptor: StoredObjectDescriptor, private val file: File) {

    fun inputStream() = file.inputStream()

    fun byteChannel() = Files.newByteChannel(file.toPath(), StandardOpenOption.READ)

}
