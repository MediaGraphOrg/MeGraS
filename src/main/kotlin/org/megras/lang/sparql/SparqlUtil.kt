package org.megras.lang.sparql

import org.apache.jena.graph.Node
import org.apache.jena.graph.Triple
import org.apache.jena.query.DatasetFactory
import org.apache.jena.query.QueryExecution
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.sparql.core.DatasetGraphFactory
import org.megras.data.graph.*
import org.megras.graphstore.QuadSet
import org.megras.lang.ResultTable
import org.megras.lang.sparql.jena.JenaGraphWrapper

object SparqlUtil {

    private val model = ModelFactory.createDefaultModel()

    fun select(query: String, quads: QuadSet): ResultTable {
        val jenaWrapper = JenaGraphWrapper(quads)
        val resultSet =
            QueryExecution.create(query, DatasetFactory.wrap(DatasetGraphFactory.wrap(jenaWrapper))).execSelect()
        val rows = mutableListOf<Map<String, QuadValue>>()
        resultSet.forEach { row ->
            val map = HashMap<String, QuadValue>()
            row.varNames().forEach { name ->
                val node = row[name].asNode()
                map[name] = toQuadValue(node)!!
            }
            rows.add(map)
        }
        return ResultTable(rows)
    }

    internal fun toQuadValue(node: Node): QuadValue? {

        if (node.isLiteral) {
            return QuadValue.of(node.literalValue)
        }

        if (node.isURI) {
            return QuadValue.of("<${node.uri}>")
        }

        return null
    }

    internal fun toTriple(quad: Quad): Triple = Triple.create(
        toNode(quad.subject),
        toNode(quad.predicate, true),
        toNode(quad.`object`)
    )


    private fun toNode(value: QuadValue, property: Boolean = false): Node = when (value) {
        is URIValue -> if (property) {
            model.createProperty("${value.prefix()}${value.suffix()}")
        } else model.createResource(
            "${value.prefix()}${value.suffix()}"
        )

        is DoubleValue -> model.createTypedLiteral(value.value)
        is LongValue -> model.createTypedLiteral(value.value)
        is StringValue -> model.createTypedLiteral(value.value)
        is VectorValue -> TODO()
    }.asNode()

}