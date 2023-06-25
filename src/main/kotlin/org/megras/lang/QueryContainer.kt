package org.megras.lang

class QueryContainer {

    internal val prefixes = HashMap<String, String>()

    internal var variables: MutableSet<String>? = mutableSetOf()

    internal var distinct = false

    internal val filterStatements = mutableListOf<Triple<String, String, String>>()

}