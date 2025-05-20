package org.megras.lang.sparql.functions.accessors.temporal

import org.apache.jena.sparql.expr.NodeValue
import org.apache.jena.sparql.function.FunctionBase1
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.graph.URIValue
import org.megras.graphstore.MutableQuadSet
import org.megras.lang.sparql.SparqlUtil
import org.megras.util.FileUtil

class CreatedAccessor  : FunctionBase1() {
    companion object {
        private lateinit var quadSet: MutableQuadSet
        private lateinit var objectStore: FileSystemObjectStore

        fun setQuadsAndOs(quadSet: MutableQuadSet, objectStore: FileSystemObjectStore) {
            this.quadSet = quadSet
            this.objectStore = objectStore
        }
    }

    override fun exec(arg: NodeValue): NodeValue {
        // Get the subject from the argument
        val subject = SparqlUtil.toQuadValue(arg.asNode()) as URIValue?
            ?: throw IllegalArgumentException("Invalid subject")

        val osId = FileUtil.getOsId(subject, quadSet) ?: TODO("Not yet implemented")

        TODO("Not yet implemented")
    }
}