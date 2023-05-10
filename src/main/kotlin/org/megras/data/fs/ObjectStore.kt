package org.megras.data.fs

import org.megras.data.fs.file.PseudoFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

interface ObjectStore {

    fun idFromStream(stream: InputStream): StoredObjectId

    @Throws(FileNotFoundException::class, IOException::class)
    fun store(file: File): StoredObjectDescriptor

    fun store(file: PseudoFile): StoredObjectDescriptor

    fun exists(file: PseudoFile): StoredObjectDescriptor?

    fun store(stream: InputStream, descriptor: StoredObjectDescriptor)

    fun get(id: StoredObjectId): ObjectStoreResult?

    fun storeDescriptor(descriptor: StoredObjectDescriptor)

    fun storageFile(id: StoredObjectId): File

    fun descriptorFile(id: StoredObjectId): File
}