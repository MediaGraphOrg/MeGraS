package org.megras.lang.sparql

import org.apache.jena.graph.Node
import org.apache.jena.graph.Triple
import org.apache.jena.query.DatasetFactory
import org.apache.jena.query.QueryExecution
import org.apache.jena.query.Syntax
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.sparql.core.DatasetGraphFactory
import org.apache.jena.query.QueryFactory
import org.apache.jena.query.Query
import org.megras.data.graph.*
import org.megras.data.model.Config
import org.megras.graphstore.QuadSet
import org.megras.lang.ResultTable
import org.megras.lang.sparql.jena.JenaGraphWrapper
import org.megras.lang.sparql.jena.batch.BatchingQueryEngineFactory
import org.megras.util.TimingConfig
import org.slf4j.LoggerFactory

object SparqlUtil {

    private val model = ModelFactory.createDefaultModel()
    private val logger = LoggerFactory.getLogger(this.javaClass)

    private val TIMING_ENABLED get() = TimingConfig.enabled

    @Volatile
    private var currentEngineType: Config.SparqlQueryEngine? = null

    /**
     * Configures the SPARQL query engine type to use.
     * Should be called once at application startup based on the configuration.
     * @param engineType The query engine type to use
     */
    fun configureQueryEngine(engineType: Config.SparqlQueryEngine) {
        if (currentEngineType == engineType) {
            return // Already configured
        }

        when (engineType) {
            Config.SparqlQueryEngine.BATCHING -> {
                BatchingQueryEngineFactory.register()
                logger.info("SPARQL query engine configured: BATCHING (optimized)")
            }
            Config.SparqlQueryEngine.DEFAULT -> {
                BatchingQueryEngineFactory.unregister()
                logger.info("SPARQL query engine configured: DEFAULT (Jena)")
            }
        }
        currentEngineType = engineType
    }

    /**
     * Keywords whose presence would cause Jena ARQ to issue outbound HTTP
     * requests from the server (SSRF). They are checked before execution:
     *
     *  - `SERVICE <url>`: ARQ federates the pattern to a remote endpoint.
     *  - `FROM <url>` / `FROM NAMED <url>`: ARQ loads the named graph via HTTP.
     *  - `LOAD` / `FETCH`: update/deprecated forms that fetch remote data
     *    (defense-in-depth; the select path itself does not execute them).
     *
     * MeGraS wraps a local QuadSet as its only dataset, so there is no
     * legitimate need for these clauses against external endpoints. Rejecting
     * them closes the most direct SSRF vector exposed by /query/sparql.
     */
    private val REMOTE_CLAUSE_KEYWORDS = setOf(
        "SERVICE", "FROM", "LOAD", "FETCH"
    )

    /**
     * Scans [query] for [REMOTE_CLAUSE_KEYWORDS] occurring as SPARQL tokens
     * (i.e. outside line comments and string/IRI literals) and rejects the
     * query if any are found. Returns the parsed [Query] for downstream
     * execution.
     *
     * The scan is intentionally conservative: it walks the text character by
     * character, skipping `#` line comments and the SPARQL literal forms
     * (`"`, `"""`, `'`, `<`), so a keyword appearing inside a string literal
     * or IRI is not treated as an instruction.
     */
    private fun parseAndRejectRemoteClauses(query: String): Query {
        val forbidden = findForbiddenKeywords(query)
        if (forbidden.isNotEmpty()) {
            throw IllegalArgumentException(
                "Remote SPARQL clauses are disabled to prevent SSRF: ${forbidden.joinToString()}"
            )
        }
        return QueryFactory.create(query, Syntax.syntaxARQ)
    }

    private fun findForbiddenKeywords(query: String): Set<String> {
        val found = mutableSetOf<String>()
        var i = 0
        val n = query.length
        while (i < n) {
            val c = query[i]
            when {
                // line comment until end of line
                c == '#' -> {
                    while (i < n && query[i] != '\n') i++
                }
                // long string literal """ ... """
                c == '"' && i + 2 < n && query[i + 1] == '"' && query[i + 2] == '"' -> {
                    i += 3
                    while (i + 2 < n && !(query[i] == '"' && query[i + 1] == '"' && query[i + 2] == '"')) i++
                    i += 3
                }
                // single/double-quoted string literal with escape support
                c == '"' || c == '\'' -> {
                    val quote = c
                    i++
                    while (i < n) {
                        if (query[i] == '\\') { i += 2; continue }
                        if (query[i] == quote) { i++; break }
                        i++
                    }
                }
                // IRI literal <...>
                c == '<' -> {
                    i++
                    while (i < n && query[i] != '>') i++
                    if (i < n) i++ // consume '>'
                }
                else -> {
                    if (c.isLetter() || c == '_') {
                        val start = i
                        while (i < n && (query[i].isLetterOrDigit() || query[i] == '_' || query[i] == '-')) i++
                        val token = query.substring(start, i).uppercase()
                        if (token in REMOTE_CLAUSE_KEYWORDS) {
                            found.add(token)
                        }
                    } else {
                        i++
                    }
                }
            }
        }
        return found
    }

    fun select(query: String, quads: QuadSet): ResultTable {

        // Log which query engine is configured
        logger.info("Executing SPARQL query with engine: ${currentEngineType ?: "NOT CONFIGURED (will use Jena default)"}")

        val startTotal = if (TIMING_ENABLED) System.currentTimeMillis() else 0L

        // STEP 1: JenaGraphWrapper instantiation
        val start1 = if (TIMING_ENABLED) System.currentTimeMillis() else 0L
        val jenaWrapper = JenaGraphWrapper(quads)
        if (TIMING_ENABLED) logger.info("Time spent in JenaGraphWrapper instantiation: ${System.currentTimeMillis() - start1}ms")

        // STEP 2: Query Execution setup and run (Jena Parsing, Planning, and DB Calls)
        val start2 = if (TIMING_ENABLED) System.currentTimeMillis() else 0L
        // Reject clauses that make Jena issue outbound HTTP from the server
        // (SERVICE / FROM / FROM NAMED) before handing the query to the engine.
        val parsedQuery = parseAndRejectRemoteClauses(query)
        val resultSet =
            QueryExecution.create(parsedQuery, DatasetFactory.wrap(DatasetGraphFactory.wrap(jenaWrapper))).execSelect()
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


    internal fun toNode(value: QuadValue, property: Boolean = false): Node = when (value) {
        is LocalQuadValue -> if (property) {
            model.createProperty(value.value)
        } else model.createResource(value.value)

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
