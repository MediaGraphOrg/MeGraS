package org.megras.lang.sparql.functions

import org.apache.jena.sparql.expr.NodeValue
import org.apache.jena.sparql.function.FunctionBase3
import org.megras.data.graph.QuadValue
import org.megras.id.QuadValueCanonical
import org.megras.lang.sparql.SparqlUtil

/**
 * SPARQL function `id(?s, ?p, ?o)` -> string literal holding the base64
 * multihash content hash of the (s,p,o) operand triple. The value is the same
 * one [org.megras.id.id] computes and that [org.megras.api.rest.handlers.QuadByIdRequestHandler]
 * fetches, so FILTER(id(?s,?p,?o) = "...") or BIND(id(?s,?p,?o) AS ?qid) is
 * consistent with the REST projection and reverse lookup.
 *
 * Pure function of already-bound terms: evaluates AFTER the enclosing pattern
 * binds, so it offers no index pushdown. A broad pattern (`?s ?p ?o`) plus a
 * FILTER on this function scans every match before filtering; reserve it for
 * restricted patterns or diagnostic/BIND use. An indexed reverse-fetch is the
 * property-function counterpart (see [org.megras.lang.sparql.functions.IdTermsPropertyFunction]).
 *
 * Term conversion goes through [SparqlUtil.toQuadValue]; a term that cannot be
 * converted (or any null operand) raises an evaluation error, which SPARQL
 * semantics propagate as an effective-error in FILTER (row excluded) and an
 * unbound in BIND.
 */
class QuadSemanticIdFunction : FunctionBase3() {
    override fun exec(a1: NodeValue, a2: NodeValue, a3: NodeValue): NodeValue {
        val s: QuadValue = SparqlUtil.toQuadValue(a1.asNode()) ?: raise()
        val p: QuadValue = SparqlUtil.toQuadValue(a2.asNode()) ?: raise()
        val o: QuadValue = SparqlUtil.toQuadValue(a3.asNode()) ?: raise()
        val id = QuadValueCanonical.semanticId(s, p, o)
        return NodeValue.makeString(id.toString())
    }

    private fun raise(): Nothing =
        throw org.apache.jena.sparql.expr.ExprEvalException("id(): unconvertible term")
}
