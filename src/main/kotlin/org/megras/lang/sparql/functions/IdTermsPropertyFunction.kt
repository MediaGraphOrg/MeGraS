package org.megras.lang.sparql.functions

import org.apache.jena.graph.Node
import org.apache.jena.sparql.core.Var
import org.apache.jena.sparql.engine.ExecutionContext
import org.apache.jena.sparql.engine.QueryIterator
import org.apache.jena.sparql.engine.binding.BindingBuilder
import org.apache.jena.sparql.engine.iterator.QueryIterNullIterator
import org.apache.jena.sparql.engine.iterator.QueryIterSingleton
import org.apache.jena.sparql.pfunction.PropFuncArg
import org.apache.jena.sparql.pfunction.PropFuncArgType
import org.apache.jena.sparql.pfunction.PropertyFunctionEval
import org.megras.data.graph.Quad
import org.megras.graphstore.QuadSet
import org.megras.id.SemanticId
import org.megras.lang.sparql.SparqlUtil

/**
 * Indexed reverse-fetch as a SPARQL property function. Pattern shape:
 *   `(?id) <${Constants.SPARQL_PREFIX}#ID_TERMS> (?s ?p ?o)`
 * Both subject and object positions are list args. Given a bound id literal
 * in the subject slot, emits a SINGLE row binding (s,p,o) to the three object
 * vars via [QuadSet.getId] — an index lookup, no scan, the same path the REST
 * endpoint uses. Refuses (zero rows) when:
 *   - the subject slot is not a single literal (id must be a concrete value;
 *     the function does NOT enumerate ids, which would require a full table
 *     walk and defeats the purpose of an indexed fetch),
 *   - the id is malformed or unknown,
 *   - the object slot is not exactly three vars, or a var is already bound in
 *     the incoming binding to a node that disagrees with the looked-up term.
 *
 * Composition caveat (why this is only a partial win pre-reification):
 * a property function joins its results into the enclosing plan by shared
 * variables — here (s,p,o). That join composes only if another pattern in the
 * query already constrains or produces those terms; the id itself is NOT a
 * graph term until reification (P3) lands, so an id cannot flow in from
 * another pattern as a variable. Today this PF is a constant-key lookup that
 * duplicates the REST endpoint inside SPARQL; the join/pushdown value
 * materialises once ids become terms. See BatchingOpExecutor for the
 * reserved pushdown hook.
 *
 * State injection: like the accessor functions, the QuadSet is held in a
 * static set at registration time (FunctionRegistrar); the PF has no other
 * channel to reach it.
 */
class IdTermsPropertyFunction private constructor(
    private val quadSet: QuadSet
) : PropertyFunctionEval(PropFuncArgType.PF_ARG_LIST, PropFuncArgType.PF_ARG_LIST) {

    @Suppress("unused") // invoked reflectively by PropertyFunctionRegistry
    constructor() : this(
        IdTermsState.quadSet ?: throw IllegalStateException("IdTermsPropertyFunction registered before setQuadSet")
    )

    override fun execEvaluated(
        binding: org.apache.jena.sparql.engine.binding.Binding,
        subjArg: PropFuncArg,
        predNode: Node,
        objArg: PropFuncArg,
        execCtxt: ExecutionContext
    ): QueryIterator {
        // subject slot: must be a single-node list carrying an id literal.
        if (!subjArg.isList || subjArg.getArgListSize() != 1) return empty(execCtxt)
        val idNode = subst(subjArg.getArg(0), binding) ?: return empty(execCtxt)
        if (!idNode.isLiteral) return empty(execCtxt)
        val sid = SemanticId.fromString(idNode.literal.toString()) ?: return empty(execCtxt)

        // object slot: exactly three vars (s,p,o). No partial constants — the
        // caller asks for the terms of a specific quad, not a filtered subset.
        if (!objArg.isList || objArg.getArgListSize() != 3) return empty(execCtxt)
        val sv = asVar(objArg.getArg(0)) ?: return empty(execCtxt)
        val pv = asVar(objArg.getArg(1)) ?: return empty(execCtxt)
        val ov = asVar(objArg.getArg(2)) ?: return empty(execCtxt)

        val quad: Quad = quadSet.getId(sid) ?: return empty(execCtxt)
        val triple = SparqlUtil.toTriple(quad)
        val ns = triple.subject
        val np = triple.predicate
        val no = triple.`object`

        // Honor an incoming binding: if any of these vars is already bound,
        // the looked-up term must agree, else this branch contributes nothing.
        val b = BindingBuilder.create(binding)
        if (!unify(b, sv, ns) || !unify(b, pv, np) || !unify(b, ov, no)) return empty(execCtxt)
        return QueryIterSingleton.create(b.build(), execCtxt)
    }

    private fun subst(node: Node, binding: org.apache.jena.sparql.engine.binding.Binding): Node? =
        if (node.isVariable) {
            val v = Var.alloc(node)
            binding.get(v) ?: null
        } else node

    private fun asVar(node: Node): Var? =
        if (node.isVariable) Var.alloc(node) else null

    /**
     * Refuses to set a var already bound to a disagreeing node; returns false
     * so the caller yields zero rows on a clash rather than emitting an
     * inconsistent binding.
     */
    private fun unify(
        b: BindingBuilder,
        v: Var,
        n: Node
    ): Boolean {
        val existing = b.get(v)
        return when {
            existing == null -> { b.set(v, n); true }
            existing == n -> true
            else -> false
        }
    }

    private fun empty(execCtxt: ExecutionContext): QueryIterator =
        QueryIterNullIterator.create(execCtxt)

    companion object {
        fun setQuadSet(q: QuadSet) { IdTermsState.quadSet = q }
    }
}

private object IdTermsState {
    @Volatile var quadSet: QuadSet? = null
}
