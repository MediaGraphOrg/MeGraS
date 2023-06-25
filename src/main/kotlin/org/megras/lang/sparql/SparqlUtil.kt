package org.megras.lang.sparql

import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.megras.lang.QueryContainer

object SparqlUtil {

    fun parse(sparql: String): QueryContainer {

        val lexer = org.megras.lang.sparql.SparqlLexer(CharStreams.fromString(sparql))
        val tokens = CommonTokenStream(lexer)
        val parser = org.megras.lang.sparql.SparqlParser(tokens)

        val visitor = SparqQuerylVisitor()
        val container = visitor.visit(parser.query())

        return container!!

    }

}