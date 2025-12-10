package org.megras.lang.sparql.jena.batch

import org.apache.jena.graph.Node
import org.apache.jena.graph.Triple
import org.apache.jena.sparql.algebra.Op
import org.apache.jena.sparql.algebra.op.*
import org.apache.jena.sparql.core.BasicPattern
import org.apache.jena.sparql.core.Var
import org.apache.jena.sparql.engine.ExecutionContext
import org.apache.jena.sparql.engine.QueryIterator
import org.apache.jena.sparql.engine.binding.Binding
import org.apache.jena.sparql.engine.binding.BindingBuilder
import org.apache.jena.sparql.engine.iterator.*
import org.apache.jena.sparql.engine.main.QC
import org.apache.jena.sparql.expr.ExprList
import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.graphstore.QuadSet
import org.megras.lang.sparql.SparqlUtil.toQuadValue
import org.megras.lang.sparql.SparqlUtil.toTriple
import org.slf4j.LoggerFactory

/**
 * Custom OpExecutor that implements result set batching for BGP execution.
 *
 * Instead of executing each triple pattern separately and then joining
 * (causing N+1 database calls), this executor:
 * 1. Collects all variable bindings from previous results
 * 2. Performs a single batched call to QuadSet.filter with all possible values
 * 3. Joins the results in memory
 */
class BatchingOpExecutor(
    private val execCxt: ExecutionContext,
    private val quadSet: QuadSet
) {

    companion object {
        private val logger = LoggerFactory.getLogger(BatchingOpExecutor::class.java)

        // Batch size limit to avoid overly large queries
        const val MAX_BATCH_SIZE = 10000

        // Enable timing logs for debugging
        private const val TIMING_ENABLED = false
    }

    /**
     * Main entry point - execute an operation with an initial binding.
     */
    fun executeOp(op: Op, binding: Binding): QueryIterator {
        val input = QueryIterRoot.create(binding, execCxt)
        return execute(op, input)
    }

    /**
     * Execute an operation with an input iterator of bindings.
     */
    private fun execute(op: Op, input: QueryIterator): QueryIterator {
        if (TIMING_ENABLED) {
            logger.info("Executing Op: ${op.javaClass.simpleName}")
        }
        return when (op) {
            is OpBGP -> executeBGP(op.pattern, input)
            is OpJoin -> executeJoin(op, input)
            is OpLeftJoin -> executeLeftJoin(op, input)
            is OpFilter -> executeFilter(op, input)
            is OpProject -> executeProject(op, input)
            is OpDistinct -> executeDistinct(op, input)
            is OpReduced -> executeReduced(op, input)
            is OpSlice -> executeSlice(op, input)
            is OpOrder -> executeOrder(op, input)
            is OpGroup -> executeGroup(op, input)
            is OpExtend -> executeExtend(op, input)
            is OpUnion -> executeUnion(op, input)
            is OpTable -> executeTable(op, input)
            is OpSequence -> executeSequence(op, input)
            else -> {
                // Fall back to default Jena execution for unsupported ops
                logger.debug("Falling back to default executor for: ${op.javaClass.simpleName}")
                QC.execute(op, input, execCxt)
            }
        }
    }

    /**
     * Execute a Basic Graph Pattern (BGP) with batching optimization.
     * This is the core of the N+1 problem solution.
     */
    private fun executeBGP(pattern: BasicPattern, input: QueryIterator): QueryIterator {
        val startTime = if (TIMING_ENABLED) System.currentTimeMillis() else 0L

        val triples = pattern.list
        if (triples.isEmpty()) {
            return input
        }

        // Materialize input bindings so we can use them for batching
        val inputBindings = materializeBindings(input)
        if (inputBindings.isEmpty()) {
            return QueryIterNullIterator.create(execCxt)
        }

        if (TIMING_ENABLED) {
            logger.info("BGP starting with ${inputBindings.size} input bindings")
            if (inputBindings.isNotEmpty()) {
                val sampleBinding = inputBindings.first()
                val boundVars = sampleBinding.vars().asSequence().toList()
                logger.info("Input binding vars: $boundVars")
            }
        }

        var currentBindings = inputBindings

        // Process each triple pattern with batching
        for (triple in triples) {
            currentBindings = executeBatchedTriplePattern(triple, currentBindings)
            if (currentBindings.isEmpty()) {
                break
            }
        }

        if (TIMING_ENABLED) {
            logger.info("BGP execution time: ${System.currentTimeMillis() - startTime}ms, results: ${currentBindings.size}")
        }

        return bindingsToIterator(currentBindings)
    }

    /**
     * Execute a single triple pattern against multiple input bindings using batching.
     * Instead of N separate calls, we make one call with all possible values.
     */
    private fun executeBatchedTriplePattern(triple: Triple, inputBindings: List<Binding>): List<Binding> {
        val startTime = if (TIMING_ENABLED) System.currentTimeMillis() else 0L

        if (TIMING_ENABLED) {
            logger.info("Executing triple pattern: ${triple.subject} ${triple.predicate} ${triple.`object`}")
        }

        // Collect all possible values for each position (S, P, O) based on current bindings
        val subjectValues = mutableSetOf<QuadValue>()
        val predicateValues = mutableSetOf<QuadValue>()
        val objectValues = mutableSetOf<QuadValue>()

        val subjectVar = if (triple.subject.isVariable) Var.alloc(triple.subject) else null
        val predicateVar = if (triple.predicate.isVariable) Var.alloc(triple.predicate) else null
        val objectVar = if (triple.`object`.isVariable) Var.alloc(triple.`object`) else null

        // For each position, either use the constant value or collect all bound values
        var hasSubjectBinding = false
        var hasPredicateBinding = false
        var hasObjectBinding = false

        for (binding in inputBindings) {
            // Subject
            if (subjectVar != null && binding.contains(subjectVar)) {
                val node = binding.get(subjectVar)
                toQuadValue(node)?.let { subjectValues.add(it) }
                hasSubjectBinding = true
            } else if (!triple.subject.isVariable) {
                toQuadValue(triple.subject)?.let { subjectValues.add(it) }
            }

            // Predicate
            if (predicateVar != null && binding.contains(predicateVar)) {
                val node = binding.get(predicateVar)
                toQuadValue(node)?.let { predicateValues.add(it) }
                hasPredicateBinding = true
            } else if (!triple.predicate.isVariable) {
                toQuadValue(triple.predicate)?.let { predicateValues.add(it) }
            }

            // Object
            if (objectVar != null && binding.contains(objectVar)) {
                val node = binding.get(objectVar)
                toQuadValue(node)?.let { objectValues.add(it) }
                hasObjectBinding = true
            } else if (!triple.`object`.isVariable) {
                toQuadValue(triple.`object`)?.let { objectValues.add(it) }
            }
        }

        // Determine filter collections
        // null means "any value" (wildcard)
        val subjectFilter: Collection<QuadValue>? = when {
            !triple.subject.isVariable -> toQuadValue(triple.subject)?.let { listOf(it) }
            hasSubjectBinding && subjectValues.isNotEmpty() ->
                if (subjectValues.size <= MAX_BATCH_SIZE) subjectValues else null
            else -> null
        }

        val predicateFilter: Collection<QuadValue>? = when {
            !triple.predicate.isVariable -> toQuadValue(triple.predicate)?.let { listOf(it) }
            hasPredicateBinding && predicateValues.isNotEmpty() ->
                if (predicateValues.size <= MAX_BATCH_SIZE) predicateValues else null
            else -> null
        }

        val objectFilter: Collection<QuadValue>? = when {
            !triple.`object`.isVariable -> toQuadValue(triple.`object`)?.let { listOf(it) }
            hasObjectBinding && objectValues.isNotEmpty() ->
                if (objectValues.size <= MAX_BATCH_SIZE) objectValues else null
            else -> null
        }

        if (TIMING_ENABLED) {
            logger.info("Batched filter - subjects: ${subjectFilter?.size ?: "all"}, " +
                       "predicates: ${predicateFilter?.size ?: "all"}, " +
                       "objects: ${objectFilter?.size ?: "all"}")
        }

        // Execute single batched query
        val matchingQuads = quadSet.filter(subjectFilter, predicateFilter, objectFilter)

        if (TIMING_ENABLED) {
            logger.info("Batched query returned ${matchingQuads.size} quads in ${System.currentTimeMillis() - startTime}ms")
        }

        // Build an index for fast lookup
        val quadIndex = buildQuadIndex(matchingQuads)

        // Join results with input bindings
        val results = mutableListOf<Binding>()

        for (binding in inputBindings) {
            val compatibleQuads = findCompatibleQuads(triple, binding, quadIndex)

            for (quad in compatibleQuads) {
                val newBinding = extendBinding(binding, triple, quad)
                if (newBinding != null) {
                    results.add(newBinding)
                }
            }
        }

        if (TIMING_ENABLED) {
            logger.info("Triple pattern join produced ${results.size} bindings")
        }

        return results
    }

    /**
     * Index structure for fast quad lookup during joining.
     */
    private data class QuadIndex(
        val bySubject: Map<QuadValue, List<Quad>>,
        val byPredicate: Map<QuadValue, List<Quad>>,
        val byObject: Map<QuadValue, List<Quad>>,
        val all: List<Quad>
    )

    /**
     * Build an index structure for fast quad lookup during joining.
     */
    private fun buildQuadIndex(quads: QuadSet): QuadIndex {
        val bySubject = mutableMapOf<QuadValue, MutableList<Quad>>()
        val byPredicate = mutableMapOf<QuadValue, MutableList<Quad>>()
        val byObject = mutableMapOf<QuadValue, MutableList<Quad>>()
        val all = mutableListOf<Quad>()

        for (quad in quads) {
            bySubject.getOrPut(quad.subject) { mutableListOf() }.add(quad)
            byPredicate.getOrPut(quad.predicate) { mutableListOf() }.add(quad)
            byObject.getOrPut(quad.`object`) { mutableListOf() }.add(quad)
            all.add(quad)
        }

        return QuadIndex(bySubject, byPredicate, byObject, all)
    }

    /**
     * Find quads that are compatible with the current binding.
     * Uses the most selective index based on which variables are bound.
     */
    private fun findCompatibleQuads(
        triple: Triple,
        binding: Binding,
        quadIndex: QuadIndex
    ): Iterable<Quad> {
        val subjectVar = if (triple.subject.isVariable) Var.alloc(triple.subject) else null
        val predicateVar = if (triple.predicate.isVariable) Var.alloc(triple.predicate) else null
        val objectVar = if (triple.`object`.isVariable) Var.alloc(triple.`object`) else null

        // Check if subject variable is bound
        if (subjectVar != null && binding.contains(subjectVar)) {
            val boundValue = toQuadValue(binding.get(subjectVar))
            if (boundValue != null) {
                return quadIndex.bySubject[boundValue] ?: emptyList()
            }
        }

        // Check if object variable is bound (common in joins)
        if (objectVar != null && binding.contains(objectVar)) {
            val boundValue = toQuadValue(binding.get(objectVar))
            if (boundValue != null) {
                return quadIndex.byObject[boundValue] ?: emptyList()
            }
        }

        // Check if predicate variable is bound (less common)
        if (predicateVar != null && binding.contains(predicateVar)) {
            val boundValue = toQuadValue(binding.get(predicateVar))
            if (boundValue != null) {
                return quadIndex.byPredicate[boundValue] ?: emptyList()
            }
        }

        // No bound variables match - return all quads (Cartesian product case)
        return quadIndex.all
    }

    /**
     * Extend a binding with values from a matching quad.
     * Returns null if the quad doesn't match the binding constraints.
     */
    private fun extendBinding(binding: Binding, triple: Triple, quad: Quad): Binding? {
        val builder = BindingBuilder.create(binding)

        // Check and bind subject
        if (!bindNode(builder, triple.subject, quad.subject, binding)) {
            return null
        }

        // Check and bind predicate
        if (!bindNode(builder, triple.predicate, quad.predicate, binding)) {
            return null
        }

        // Check and bind object
        if (!bindNode(builder, triple.`object`, quad.`object`, binding)) {
            return null
        }

        return builder.build()
    }

    /**
     * Bind a node from a triple pattern to a value from a quad.
     * Returns false if there's a conflict with an existing binding.
     */
    private fun bindNode(builder: BindingBuilder, patternNode: Node, quadValue: QuadValue, existingBinding: Binding): Boolean {
        if (patternNode.isVariable) {
            val variable = Var.alloc(patternNode)
            val quadNode = quadValueToNode(quadValue)

            if (existingBinding.contains(variable)) {
                // Variable already bound - check if values match
                val existingNode = existingBinding.get(variable)
                if (existingNode != quadNode) {
                    return false
                }
            } else {
                // Bind the variable
                builder.add(variable, quadNode)
            }
        } else {
            // Constant in pattern - check if it matches the quad value
            val patternValue = toQuadValue(patternNode)
            if (patternValue != quadValue) {
                return false
            }
        }
        return true
    }

    /**
     * Convert a QuadValue to a Jena Node.
     */
    private fun quadValueToNode(value: QuadValue): Node {
        // Create a dummy quad to use the existing toTriple conversion
        val dummyQuad = Quad(value, value, value)
        return toTriple(dummyQuad).subject
    }

    /**
     * Execute a join operation.
     */
    private fun executeJoin(op: OpJoin, input: QueryIterator): QueryIterator {
        if (TIMING_ENABLED) {
            logger.info("Executing JOIN - left: ${op.left.javaClass.simpleName}, right: ${op.right.javaClass.simpleName}")
        }
        val left = execute(op.left, input)
        return execute(op.right, left)
    }

    /**
     * Execute a left join (OPTIONAL).
     */
    private fun executeLeftJoin(op: OpLeftJoin, input: QueryIterator): QueryIterator {
        val leftResults = materializeBindings(execute(op.left, input))
        val resultBindings = mutableListOf<Binding>()

        for (binding in leftResults) {
            val rightInput = bindingsToIterator(listOf(binding))
            val rightResults = materializeBindings(execute(op.right, rightInput))

            if (rightResults.isNotEmpty()) {
                // Apply filter if present
                val filtered = if (op.exprs != null && !op.exprs.isEmpty) {
                    rightResults.filter { evaluateExprs(op.exprs, it) }
                } else {
                    rightResults
                }

                if (filtered.isNotEmpty()) {
                    resultBindings.addAll(filtered)
                } else {
                    resultBindings.add(binding)
                }
            } else {
                resultBindings.add(binding)
            }
        }

        return bindingsToIterator(resultBindings)
    }

    /**
     * Execute a filter operation.
     */
    private fun executeFilter(op: OpFilter, input: QueryIterator): QueryIterator {
        val subResults = execute(op.subOp, input)
        // Apply each filter expression in sequence
        var result = subResults
        for (expr in op.exprs) {
            result = QueryIterFilterExpr(result, expr, execCxt)
        }
        return result
    }

    /**
     * Evaluate filter expressions against a binding.
     */
    private fun evaluateExprs(exprs: ExprList, binding: Binding): Boolean {
        return try {
            for (expr in exprs) {
                val result = expr.eval(binding, execCxt)
                if (!result.boolean) {
                    return false
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Execute a project operation.
     */
    private fun executeProject(op: OpProject, input: QueryIterator): QueryIterator {
        val subResults = execute(op.subOp, input)
        if (TIMING_ENABLED) {
            logger.info("Project operation - projecting to vars: ${op.vars}")
        }

        // Materialize and manually project to ensure all bindings are processed correctly
        val bindings = materializeBindings(subResults)
        if (TIMING_ENABLED) {
            logger.info("Project operation - ${bindings.size} bindings to project")
        }

        val projectedVars = op.vars.toSet()
        val projectedBindings = bindings.map { binding ->
            val builder = BindingBuilder.create()
            for (v in projectedVars) {
                if (binding.contains(v)) {
                    builder.add(v, binding.get(v))
                }
            }
            builder.build()
        }

        if (TIMING_ENABLED) {
            logger.info("Project operation - produced ${projectedBindings.size} projected bindings")
        }

        return bindingsToIterator(projectedBindings)
    }

    /**
     * Execute a distinct operation.
     */
    private fun executeDistinct(op: OpDistinct, input: QueryIterator): QueryIterator {
        val subResults = execute(op.subOp, input)
        // Manually implement distinct by collecting unique bindings
        if (TIMING_ENABLED) {
            logger.info("Distinct operation - about to materialize sub-results")
        }
        val bindings = materializeBindings(subResults)
        if (TIMING_ENABLED) {
            logger.info("Distinct operation - materialized ${bindings.size} bindings from sub-results")
        }
        val distinctBindings = bindings.distinctBy { binding ->
            // Create a key based on all variable values
            binding.vars().asSequence().map { v -> Pair(v, binding.get(v)) }.toSet()
        }
        if (TIMING_ENABLED) {
            logger.info("Distinct operation - ${bindings.size} bindings reduced to ${distinctBindings.size}")
            // Verify the list is correct
            logger.info("Distinct operation - returning iterator with ${distinctBindings.size} bindings, first binding vars: ${distinctBindings.firstOrNull()?.vars()?.asSequence()?.toList()}")
        }
        return bindingsToIterator(distinctBindings)
    }

    /**
     * Execute a reduced operation.
     */
    private fun executeReduced(op: OpReduced, input: QueryIterator): QueryIterator {
        val subResults = execute(op.subOp, input)
        // REDUCED is a weaker form of DISTINCT - same implementation for correctness
        val bindings = materializeBindings(subResults)
        val distinctBindings = bindings.distinctBy { binding ->
            binding.vars().asSequence().map { v -> Pair(v, binding.get(v)) }.toSet()
        }
        if (TIMING_ENABLED) {
            logger.info("Reduced operation - ${bindings.size} bindings reduced to ${distinctBindings.size}")
        }
        return bindingsToIterator(distinctBindings)
    }

    /**
     * Execute a slice (LIMIT/OFFSET) operation.
     */
    private fun executeSlice(op: OpSlice, input: QueryIterator): QueryIterator {
        if (TIMING_ENABLED) {
            logger.info("Slice operation - start: ${op.start}, length: ${op.length}")
        }
        val subResults = execute(op.subOp, input)
        return QueryIterSlice(subResults, op.start, op.length, execCxt)
    }

    /**
     * Execute an order (ORDER BY) operation.
     */
    private fun executeOrder(op: OpOrder, input: QueryIterator): QueryIterator {
        val subResults = execute(op.subOp, input)
        if (TIMING_ENABLED) {
            logger.info("Order operation - sorting by: ${op.conditions}")
        }
        // Materialize bindings first
        val bindings = materializeBindings(subResults)
        if (TIMING_ENABLED) {
            logger.info("Order operation - ${bindings.size} bindings to sort")
        }

        // Sort bindings using Jena's comparator
        val comparator = org.apache.jena.sparql.engine.binding.BindingComparator(op.conditions, execCxt)
        val sortedBindings = bindings.sortedWith(comparator)

        if (TIMING_ENABLED) {
            logger.info("Order operation - sorted ${sortedBindings.size} bindings")
        }

        return bindingsToIterator(sortedBindings)
    }

    /**
     * Execute a group (GROUP BY) operation - delegate to default executor.
     */
    private fun executeGroup(op: OpGroup, input: QueryIterator): QueryIterator {
        val subResults = execute(op.subOp, input)
        // Delegate to Jena's default execution for complex GROUP BY
        return QC.execute(op, subResults, execCxt)
    }

    /**
     * Execute an extend (BIND) operation.
     */
    private fun executeExtend(op: OpExtend, input: QueryIterator): QueryIterator {
        val subResults = execute(op.subOp, input)
        return QueryIterAssign(subResults, op.varExprList, execCxt, false)
    }

    /**
     * Execute a union operation.
     */
    private fun executeUnion(op: OpUnion, input: QueryIterator): QueryIterator {
        val inputBindings = materializeBindings(input)

        val leftResults = execute(op.left, bindingsToIterator(inputBindings))
        val rightResults = execute(op.right, bindingsToIterator(inputBindings))

        // Concatenate results by materializing and combining
        val leftBindings = materializeBindings(leftResults)
        val rightBindings = materializeBindings(rightResults)
        return bindingsToIterator(leftBindings + rightBindings)
    }

    /**
     * Execute a table operation.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun executeTable(op: OpTable, input: QueryIterator): QueryIterator {
        return op.table.iterator(execCxt)
    }

    /**
     * Execute a sequence operation.
     */
    private fun executeSequence(op: OpSequence, input: QueryIterator): QueryIterator {
        var current = input
        for (subOp in op.elements) {
            current = execute(subOp, current)
        }
        return current
    }

    /**
     * Materialize all bindings from an iterator into a list.
     */
    private fun materializeBindings(iter: QueryIterator): List<Binding> {
        val bindings = mutableListOf<Binding>()
        while (iter.hasNext()) {
            bindings.add(iter.nextBinding())
        }
        iter.close()
        return bindings
    }

    /**
     * Convert a list of bindings back to an iterator.
     */
    private fun bindingsToIterator(bindings: List<Binding>): QueryIterator {
        if (bindings.isEmpty()) {
            return QueryIterNullIterator.create(execCxt)
        }
        // Use a copy of the list to ensure the iterator can be traversed multiple times if needed
        val bindingsList = ArrayList(bindings)
        return QueryIterPlainWrapper.create(bindingsList.iterator(), execCxt)
    }
}

