package org.megras.graphstore.implicit.handlers

import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.fs.StoredObjectId
import org.megras.data.graph.LocalQuadValue
import org.megras.data.graph.StringValue
import org.megras.data.graph.URIValue
import org.megras.data.schema.MeGraS
import org.megras.graphstore.QuadSet
import org.megras.graphstore.implicit.ImplicitRelationHandler
import org.megras.graphstore.implicit.ImplicitRelationMutableQuadSet
import org.megras.util.Constants
import org.megras.util.djl.ClipEmbeddings
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO

class NearDuplicateHandler(private val objectStore: FileSystemObjectStore) : ImplicitRelationHandler{

    override val predicate: URIValue = URIValue("${Constants.IMPLICIT_PREFIX}/nearDuplicate")

    private lateinit var quadSet: ImplicitRelationMutableQuadSet

    override fun init(quadSet: ImplicitRelationMutableQuadSet) {
        this.quadSet = quadSet
    }

    override fun findObjects(subject: URIValue): Set<URIValue> {
        if (subject !is LocalQuadValue) {
            return emptySet()
        }

        val canonicalId = quadSet.filter(
            setOf(subject),
            setOf(MeGraS.CANONICAL_ID.uri),
            null
        ).firstOrNull()?.`object` as? StringValue ?: return emptySet()

        val osId = StoredObjectId.of(canonicalId.value) ?: return emptySet()
        val path: String = objectStore.storageFile(osId).absolutePath

        val embedding = ClipEmbeddings.getImageEmbedding(path)
        println("Embedding: ${embedding.joinToString(",")}")

        TODO("Not yet implemented")
    }

    override fun findSubjects(`object`: URIValue): Set<URIValue> {
        TODO("Not yet implemented")
    }

    override fun findAll(): QuadSet {
        TODO("Not yet implemented")
    }
}