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
import org.megras.lang.sparql.jena.batch.BatchingQueryEngineFactory
import org.slf4j.LoggerFactory

object SparqlUtil {

    private val model = ModelFactory.createDefaultModel()
    private val logger = LoggerFactory.getLogger(this.javaClass)

    private const val TIMING_ENABLED = false

    init {
        // Register the custom batching query engine factory
        BatchingQueryEngineFactory.register()
    }

    fun select(query: String, quads: QuadSet): ResultTable {

        val startTotal = if (TIMING_ENABLED) System.currentTimeMillis() else 0L

        // STEP 1: JenaGraphWrapper instantiation
        val start1 = if (TIMING_ENABLED) System.currentTimeMillis() else 0L
        val jenaWrapper = JenaGraphWrapper(quads)
        if (TIMING_ENABLED) logger.info("Time spent in JenaGraphWrapper instantiation: ${System.currentTimeMillis() - start1}ms")

        // STEP 2: Query Execution setup and run (Jena Parsing, Planning, and DB Calls)
        val start2 = if (TIMING_ENABLED) System.currentTimeMillis() else 0L
        val resultSet =
            QueryExecution.create(query, DatasetFactory.wrap(DatasetGraphFactory.wrap(jenaWrapper))).execSelect()
        if (TIMING_ENABLED) logger.info("Time spent in QueryExecution setup and run: ${System.currentTimeMillis() - start2}ms")

        val rows = mutableListOf<Map<String, QuadValue>>()

        // STEP 3: Result conversion loop
        val start3 = if (TIMING_ENABLED) System.currentTimeMillis() else 0L
        var rowCount = 0
        while (resultSet.hasNext()) {
            rowCount++
            val row = resultSet.nextSolution()
            val map = HashMap<String, QuadValue>()

            row.varNames().forEach { name ->
                val node = row.get(name).asNode()
                map[name] = toQuadValue(node)!!
            }
            rows.add(map)
        }
        val end3 = System.currentTimeMillis()
        if (TIMING_ENABLED) logger.info("Time spent in Result conversion loop: ${end3 - start3}ms, processed $rowCount rows")
        if (TIMING_ENABLED) logger.info("Total rows in result: ${rows.size}")

        val resultTable = ResultTable(rows)

        if (TIMING_ENABLED) logger.info("Total time spent in SparqlUtil.select: ${System.currentTimeMillis() - startTotal}ms")

        return resultTable
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
        is VectorValue -> model.createTypedLiteral(value.toString())
        is TemporalValue -> model.createTypedLiteral(value.toString())
    }.asNode()

}