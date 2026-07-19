package org.megras.lang.sparql.functions

import org.apache.jena.sparql.pfunction.PropertyFunctionRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.graphstore.BasicQuadSet
import org.megras.id.id
import org.megras.lang.sparql.SparqlUtil
import org.megras.util.Constants

/**
 * End-to-end check that ARQ recognises the list-arg property-function form
 * `(?id) <...#ID_TERMS> (?s ?p ?o)` and routes it to [IdTermsPropertyFunction],
 * which must return exactly the stored quad's terms for a known id and nothing
 * for an unknown one. This is the fragile integration point: if ARQ stops
 * recognising list-args in subject/object positions for registered PFs, the
 * query fails to parse and this test goes red.
 *
 * Global-registration caveat: [FunctionRegistrar.register] and the Jena
 * function/property-function registries are process-global. No other test in
 * the suite exercises SPARQL against a different QuadSet, so wiring the PF to
 * a throwaway BasicQuadSet here does not interfere. Kept to one class to
 * minimise that surface.
 */
class IdTermsPropertyFunctionTest {

    private val subj = QuadValue.of("http://ex/s")!!
    private val pred = QuadValue.of("http://ex/p")!!
    private val obj = QuadValue.of("http://ex/o")!!
    private val stored = Quad(subj, pred, obj)
    private val storedId: String = stored.id.toString()
    private val idTermsUri = "<${Constants.SPARQL_PREFIX}#ID_TERMS>"

    @Test
    fun knownIdEmitsTerms() {
        val qs = BasicQuadSet(setOf(stored))
        PropertyFunctionRegistry.get().put("${Constants.SPARQL_PREFIX}#ID_TERMS", IdTermsPropertyFunction::class.java)
        IdTermsPropertyFunction.setQuadSet(qs)
        val q = """
            PREFIX meg: <${Constants.SPARQL_PREFIX}#>
            SELECT ?s ?p ?o WHERE {
              ("$storedId") meg:ID_TERMS (?s ?p ?o) .
            }
        """.trimIndent()
        val rows = SparqlUtil.select(q, qs).rows
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(subj, row["s"])
        assertEquals(pred, row["p"])
        assertEquals(obj, row["o"])
    }

    @Test
    fun unknownIdEmitsNothing() {
        val qs = BasicQuadSet(setOf(stored))
        PropertyFunctionRegistry.get().put("${Constants.SPARQL_PREFIX}#ID_TERMS", IdTermsPropertyFunction::class.java)
        IdTermsPropertyFunction.setQuadSet(qs)
        val q = """
            PREFIX meg: <${Constants.SPARQL_PREFIX}#>
            SELECT ?s ?p ?o WHERE {
              ("not-a-valid-multihash") meg:ID_TERMS (?s ?p ?o) .
            }
        """.trimIndent()
        assertTrue(SparqlUtil.select(q, qs).rows.isEmpty())
    }
}
