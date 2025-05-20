package org.megras.data.fs

import org.megras.data.graph.LocalQuadValue
import org.megras.data.graph.StringValue
import org.megras.data.graph.URIValue
import org.megras.data.schema.MeGraS
import org.megras.graphstore.QuadSet

object FileUtil {
    fun getOsId(subject: URIValue, quadSet: QuadSet): StoredObjectId? {
        if (subject !is LocalQuadValue) {
            return null
        }

        val canonicalId = quadSet.filter(
            setOf(subject),
            setOf(MeGraS.CANONICAL_ID.uri),
            null
        ).firstOrNull()?.`object` as? StringValue ?: return null

        return StoredObjectId.of(canonicalId.value)
    }

    fun getOsr(subject: URIValue, quadSet: QuadSet, objectStore: FileSystemObjectStore): ObjectStoreResult? {
        val osId = getOsId(subject, quadSet) ?: return null
        return objectStore.get(osId)
    }

    fun getPath(subject: URIValue, quadSet: QuadSet, objectStore: FileSystemObjectStore): String? {
        val osId = getOsId(subject, quadSet) ?: return null
        return objectStore.storageFile(osId).absolutePath
    }
}