package org.megras.data.graph

import java.io.Serializable

open class LocalQuadValue(public override val uri: String, infix: String = "") : URIValue(null, if (infix.isNotEmpty()) "${infix.trim()}/${clean(uri)}" else clean(uri)), Serializable{

    companion object {

        private fun clean(uri: String) : String {
            val clean = uri.trim()
            return if (clean.startsWith('/')) {
                clean.substring(1)
            } else {
                clean
            }
        }

        val defaultPrefix: String
            get() = "http://localhost/"
    }


    override fun prefix() = defaultPrefix
    override fun suffix() = uri

    override fun toString() = "<${defaultPrefix}${uri}>"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        if (!super.equals(other)) return false

        other as LocalQuadValue

        return uri == other.uri
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + uri.hashCode()
        return result
    }

}