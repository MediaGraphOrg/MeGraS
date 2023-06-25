package org.megras.lang.sparql

import org.antlr.v4.runtime.tree.TerminalNode
import org.megras.lang.QueryContainer

class SparqQuerylVisitor : SparqlBaseVisitor<QueryContainer?>() {

    private var container = QueryContainer()

    override fun visitQuery(ctx: SparqlParser.QueryContext?): QueryContainer? {
        this.container = QueryContainer()
        return super.visitQuery(ctx)
    }

    override fun visitBaseDecl(ctx: SparqlParser.BaseDeclContext?): QueryContainer? {
        return super.visitBaseDecl(ctx)
    }

    override fun visitPrefixDecl(ctx: SparqlParser.PrefixDeclContext): QueryContainer {
        container.prefixes[ctx.PNAME_NS().text] = ctx.IRI_REF().text.substringAfter('<').substringBeforeLast('>')
        return container
    }

    override fun visitSelectQuery(ctx: SparqlParser.SelectQueryContext): QueryContainer? {

        if (ctx.children[1] is TerminalNode) {
            when(ctx.children[1].text) {
                "DISTINCT" -> {
                    container.distinct = true
                }
                "REDUCED" -> {
                    println("REDUCED selection is currently not supported, ignoring")
                }
                "*" -> {
                    container.variables = null
                }
            }

        }

        if (ctx.datasetClause().isNotEmpty()) {
            println("from clause is currently not supported, ignoring")
        }

        if (container.variables != null) {
            val variables = container.variables!!
            ctx.var_().forEach {
                variables.add((it.VAR1()?.text) ?: it.VAR2()!!.text)
            }
        }



        return visitChildren(ctx)

    }

    override fun visitTriplesBlock(ctx: SparqlParser.TriplesBlockContext?): QueryContainer? {

        ctx!!.children.forEach {

            when(it) {
                is TerminalNode -> {
                    //token between statements, ignore
                }
                is SparqlParser.TriplesSameSubjectContext -> {
                    if (it.varOrTerm() != null) {
                        val s = it.varOrTerm().text!!
                        val p = it.propertyListNotEmpty().children[0].text!!
                        val o = it.propertyListNotEmpty().children[1].text!!
                        container.filterStatements.add(Triple(s, p, o))
                    } else {
                        val triplesNode = it.triplesNode()!!
                        val propertyList = it.propertyList()!!
                        println("statement not currently supported")
                    }
                }
            }

        }

        return super.visitTriplesBlock(ctx)
    }

    override fun visitTerminal(node: TerminalNode?): QueryContainer {
        return this.container
    }
}