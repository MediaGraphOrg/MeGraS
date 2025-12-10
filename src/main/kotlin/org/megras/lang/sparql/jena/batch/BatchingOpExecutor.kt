package org.megras.lang.sparql.jena.batch

import org.apache.jena.graph.Node
import org.apache.jena.graph.Triple
import org.apache.jena.query.Query
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

        // Constant indicating no limit
        const val NO_LIMIT = -1L
    }

    /**
     * Main entry point - execute an operation with an initial binding.
     */
    fun executeOp(op: Op, binding: Binding): QueryIterator {
        val input = QueryIterRoot.create(binding, execCxt)
        return execute(op, input, NO_LIMIT)
    }

    /**
     * Execute an operation with an input iterator of bindings.
     * @param limit Maximum number of results to produce (-1 for no limit)
     */
    private fun execute(op: Op, input: QueryIterator, limit: Long = NO_LIMIT): QueryIterator {
        if (TIMING_ENABLED) {
            logger.info("Executing Op: ${op.javaClass.simpleName}" + if (limit != NO_LIMIT) " with limit $limit" else "")
        }
        return when (op) {
            is OpBGP -> executeBGP(op.pattern, input, limit)
            is OpJoin -> executeJoin(op, input, limit)
            is OpLeftJoin -> executeLeftJoin(op, input, limit)
            is OpFilter -> executeFilter(op, input, limit)
            is OpProject -> executeProject(op, input, limit)
            is OpDistinct -> executeDistinct(op, input, limit)
            is OpReduced -> executeReduced(op, input, limit)
            is OpSlice -> executeSlice(op, input)
            is OpOrder -> executeOrder(op, input, limit)
            is OpGroup -> executeGroup(op, input)
            is OpExtend -> executeExtend(op, input, limit)
            is OpUnion -> executeUnion(op, input, limit)
            is OpTable -> executeTable(op, input)
            is OpSequence -> executeSequence(op, input, limit)
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
     * @param limit Maximum number of results to produce (-1 for no limit)
     */
    private fun executeBGP(pattern: BasicPattern, input: QueryIterator, limit: Long = NO_LIMIT): QueryIterator {
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
            logger.info("BGP starting with ${inputBindings.size} input bindings" + if (limit != NO_LIMIT) ", limit=$limit" else "")
            if (inputBindings.isNotEmpty()) {
                val sampleBinding = inputBindings.first()
                val boundVars = sampleBinding.vars().asSequence().toList()
                logger.info("Input binding vars: $boundVars")
            }
        }

        // Reorder patterns: most selective first (patterns with more constants)
        val reorderedTriples = reorderPatterns(triples, inputBindings.firstOrNull())

        if (TIMING_ENABLED && reorderedTriples != triples) {
            logger.info("Reordered patterns: ${reorderedTriples.map { "${it.subject} ${it.predicate} ${it.`object`}" }}")
        }

        var currentBindings = inputBindings

        // Process each triple pattern with batching
        for (triple in reorderedTriples) {
            currentBindings = executeBatchedTriplePattern(triple, currentBindings, limit)
            if (currentBindings.isEmpty()) {
                break
            }
            // Early termination if we've reached the limit
            if (limit != NO_LIMIT && currentBindings.size >= limit) {
                if (TIMING_ENABLED) {
                    logger.info("BGP early termination: reached limit of $limit results")
                }
                currentBindings = currentBindings.take(limit.toInt())
                break
            }
        }

        if (TIMING_ENABLED) {
            logger.info("BGP execution time: ${System.currentTimeMillis() - startTime}ms, results: ${currentBindings.size}")
        }

        return bindingsToIterator(currentBindings)
    }

    /**
     * Reorder triple patterns for optimal execution.
     * Patterns with more constants (especially in object position) are more selective
     * and should be executed first to reduce intermediate result sizes.
     */
    private fun reorderPatterns(triples: List<Triple>, currentBinding: Binding?): List<Triple> {
        if (triples.size <= 1) return triples

        // Calculate selectivity score for each pattern
        // Higher score = more selective = should be executed first
        fun selectivityScore(triple: Triple): Int {
            var score = 0

            // Constants are very selective
            if (!triple.subject.isVariable) score += 10
            if (!triple.predicate.isVariable) score += 5  // predicates are usually constrained anyway
            if (!triple.`object`.isVariable) score += 20  // object constants are very selective (e.g., country="Turkey")

            // Already bound variables are also selective
            if (currentBinding != null) {
                if (triple.subject.isVariable && currentBinding.contains(Var.alloc(triple.subject))) score += 8
                if (triple.predicate.isVariable && currentBinding.contains(Var.alloc(triple.predicate))) score += 3
                if (triple.`object`.isVariable && currentBinding.contains(Var.alloc(triple.`object`))) score += 8
            }

            return score
        }

        return triples.sortedByDescending { selectivityScore(it) }
    }

    /**
     * Execute a single triple pattern against multiple input bindings using batching.
     * Instead of N separate calls, we make one call with all possible values.
     * @param limit Maximum number of results to produce (-1 for no limit)
     */
    private fun executeBatchedTriplePattern(triple: Triple, inputBindings: List<Binding>, limit: Long = NO_LIMIT): List<Binding> {
        val startTime = if (TIMING_ENABLED) System.currentTimeMillis() else 0L

        if (TIMING_ENABLED) {
            logger.info("Executing triple pattern: ${triple.subject} ${triple.predicate} ${triple.`object`}" +
                    if (limit != NO_LIMIT) " with limit $limit" else "")
        }

        val subjectVar = if (triple.subject.isVariable) Var.alloc(triple.subject) else null
        val predicateVar = if (triple.predicate.isVariable) Var.alloc(triple.predicate) else null
        val objectVar = if (triple.`object`.isVariable) Var.alloc(triple.`object`) else null

        val effectiveLimit = if (limit == NO_LIMIT) Int.MAX_VALUE else limit.toInt()

        // If we have a limit and bindings to process, use incremental batching
        // to avoid fetching all data when we only need a few results
        if (limit != NO_LIMIT && inputBindings.size > limit) {
            return executeBatchedTriplePatternIncremental(
                triple, inputBindings, subjectVar, predicateVar, objectVar, effectiveLimit, startTime
            )
        }

        // Standard batch execution for no-limit or small input cases
        return executeBatchedTriplePatternFull(
            triple, inputBindings, subjectVar, predicateVar, objectVar, effectiveLimit, startTime
        )
    }

    /**
     * Incremental batch execution: process bindings in small batches until we have enough results.
     * This avoids fetching all data when we only need a few results.
     *
     * Uses subject-based batching with a smarter ordering strategy to find matches quickly.
     */
    private fun executeBatchedTriplePatternIncremental(
        triple: Triple,
        inputBindings: List<Binding>,
        subjectVar: Var?,
        @Suppress("UNUSED_PARAMETER") predicateVar: Var?,
        @Suppress("UNUSED_PARAMETER") objectVar: Var?,
        effectiveLimit: Int,
        startTime: Long
    ): List<Binding> {
        val results = mutableListOf<Binding>()

        // Group bindings by their bound subject value for efficient processing
        val bindingsBySubject: Map<QuadValue?, List<Binding>> = if (subjectVar != null) {
            inputBindings.groupBy { binding ->
                if (binding.contains(subjectVar)) toQuadValue(binding.get(subjectVar)) else null
            }
        } else {
            mapOf(null to inputBindings)
        }

        val uniqueSubjects = bindingsBySubject.keys.filterNotNull()

        // Shuffle subjects to avoid worst-case where matching subjects are at the end
        // This gives us a better chance of finding matches early with incremental batching
        val shuffledSubjects = uniqueSubjects.shuffled()

        if (TIMING_ENABLED) {
            logger.info("Using incremental batching: need $effectiveLimit results from ${inputBindings.size} bindings (${uniqueSubjects.size} unique subjects, shuffled)")
        }

        if (subjectVar != null && shuffledSubjects.isNotEmpty()) {
            // Use subject-based batching with smaller initial batch sizes
            // The filter operation has non-linear cost, so start small
            val initialBatchSize = (effectiveLimit * 2).coerceAtLeast(25).coerceAtMost(shuffledSubjects.size)
            var batchSize = initialBatchSize
            var processedSubjects = 0

            val predicateFilter: Collection<QuadValue>? = when {
                !triple.predicate.isVariable -> toQuadValue(triple.predicate)?.let { listOf(it) }
                else -> null
            }

            val objectFilter: Collection<QuadValue>? = when {
                !triple.`object`.isVariable -> toQuadValue(triple.`object`)?.let { listOf(it) }
                else -> null
            }

            while (results.size < effectiveLimit && processedSubjects < shuffledSubjects.size) {
                val batchEnd = (processedSubjects + batchSize).coerceAtMost(shuffledSubjects.size)
                val batchSubjects = shuffledSubjects.subList(processedSubjects, batchEnd)

                if (TIMING_ENABLED) {
                    logger.info("Processing subject batch: $processedSubjects to $batchEnd (${batchSubjects.size} subjects)")
                }

                val batchStartTime = System.currentTimeMillis()

                // Query with subject filter - this is usually fast when subjects are indexed
                val matchingQuads = quadSet.filter(batchSubjects, predicateFilter, objectFilter)

                if (TIMING_ENABLED) {
                    logger.info("Batch query returned ${matchingQuads.size} quads in ${System.currentTimeMillis() - batchStartTime}ms")
                }

                // Build index for fast lookup within this batch
                val quadIndex = buildQuadIndex(matchingQuads)

                // Process bindings for subjects in this batch
                for (subject in batchSubjects) {
                    if (results.size >= effectiveLimit) break

                    val bindingsForSubject = bindingsBySubject[subject] ?: continue
                    val quadsForSubject = quadIndex.bySubject[subject] ?: continue

                    for (binding in bindingsForSubject) {
                        if (results.size >= effectiveLimit) break

                        for (quad in quadsForSubject) {
                            if (results.size >= effectiveLimit) break

                            val newBinding = extendBinding(binding, triple, quad)
                            if (newBinding != null) {
                                results.add(newBinding)
                            }
                        }
                    }
                }

                processedSubjects = batchEnd

                // Double batch size for next iteration
                batchSize = (batchSize * 2).coerceAtMost(shuffledSubjects.size - processedSubjects)

                if (TIMING_ENABLED && results.size < effectiveLimit && processedSubjects < shuffledSubjects.size) {
                    logger.info("Batch produced ${results.size}/$effectiveLimit results, continuing...")
                }
            }
        } else {
            // Fallback: no subject variable bound, use standard approach
            val subjectFilter: Collection<QuadValue>? = if (!triple.subject.isVariable) {
                toQuadValue(triple.subject)?.let { listOf(it) }
            } else null

            val predicateFilter: Collection<QuadValue>? = when {
                !triple.predicate.isVariable -> toQuadValue(triple.predicate)?.let { listOf(it) }
                else -> null
            }

            val objectFilter: Collection<QuadValue>? = when {
                !triple.`object`.isVariable -> toQuadValue(triple.`object`)?.let { listOf(it) }
                else -> null
            }

            val matchingQuads = quadSet.filter(subjectFilter, predicateFilter, objectFilter)
            val quadIndex = buildQuadIndex(matchingQuads)

            for (binding in inputBindings) {
                if (results.size >= effectiveLimit) break

                val compatibleQuads = findCompatibleQuads(triple, binding, quadIndex)
                for (quad in compatibleQuads) {
                    if (results.size >= effectiveLimit) break

                    val newBinding = extendBinding(binding, triple, quad)
                    if (newBinding != null) {
                        results.add(newBinding)
                    }
                }
            }
        }

        if (TIMING_ENABLED) {
            logger.info("Incremental batching complete: ${results.size} results in ${System.currentTimeMillis() - startTime}ms")
        }

        return results
    }

    /**
     * Full batch execution: process all bindings at once (original behavior).
     */
    private fun executeBatchedTriplePatternFull(
        triple: Triple,
        inputBindings: List<Binding>,
        subjectVar: Var?,
        predicateVar: Var?,
        objectVar: Var?,
        effectiveLimit: Int,
        startTime: Long
    ): List<Binding> {
        // Collect all possible values for each position (S, P, O) based on current bindings
        val subjectValues = mutableSetOf<QuadValue>()
        val predicateValues = mutableSetOf<QuadValue>()
        val objectValues = mutableSetOf<QuadValue>()

        var hasSubjectBinding = false
        var hasPredicateBinding = false
        var hasObjectBinding = false

        for (binding in inputBindings) {
            if (subjectVar != null && binding.contains(subjectVar)) {
                toQuadValue(binding.get(subjectVar))?.let { subjectValues.add(it) }
                hasSubjectBinding = true
            } else if (!triple.subject.isVariable) {
                toQuadValue(triple.subject)?.let { subjectValues.add(it) }
            }

            if (predicateVar != null && binding.contains(predicateVar)) {
                toQuadValue(binding.get(predicateVar))?.let { predicateValues.add(it) }
                hasPredicateBinding = true
            } else if (!triple.predicate.isVariable) {
                toQuadValue(triple.predicate)?.let { predicateValues.add(it) }
            }

            if (objectVar != null && binding.contains(objectVar)) {
                toQuadValue(binding.get(objectVar))?.let { objectValues.add(it) }
                hasObjectBinding = true
            } else if (!triple.`object`.isVariable) {
                toQuadValue(triple.`object`)?.let { objectValues.add(it) }
            }
        }

        // Determine filter collections
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

        val matchingQuads = quadSet.filter(subjectFilter, predicateFilter, objectFilter)

        if (TIMING_ENABLED) {
            logger.info("Batched query returned ${matchingQuads.size} quads in ${System.currentTimeMillis() - startTime}ms")
        }

        val quadIndex = buildQuadIndex(matchingQuads)

        val results = mutableListOf<Binding>()

        for (binding in inputBindings) {
            if (results.size >= effectiveLimit) {
                if (TIMING_ENABLED) {
                    logger.info("Triple pattern join early termination: reached limit of $effectiveLimit")
                }
                break
            }

            val compatibleQuads = findCompatibleQuads(triple, binding, quadIndex)

            for (quad in compatibleQuads) {
                if (results.size >= effectiveLimit) break

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
     * Attempts to reorder operands to execute more selective patterns first.
     * @param limit Maximum number of results to produce (-1 for no limit)
     */
    private fun executeJoin(op: OpJoin, input: QueryIterator, limit: Long = NO_LIMIT): QueryIterator {
        // Flatten nested joins to allow global reordering
        val allOps = flattenJoins(op)

        if (allOps.size > 1) {
            // Reorder by selectivity - more selective first
            val reordered = reorderJoinOperands(allOps)

            if (TIMING_ENABLED) {
                logger.info("Executing JOIN with ${allOps.size} operands (reordered: ${reordered != allOps})" +
                        if (limit != NO_LIMIT) " with limit $limit" else "")
                reordered.forEach { logger.info("  - ${describeOp(it)}") }
            }

            // Execute in optimized order
            // First operand: no limit (we need all matching results to join with)
            var result = execute(reordered[0], input, NO_LIMIT)

            // Subsequent operands: propagate limit to ALL of them
            // Each step can potentially early-terminate once we have enough joined results
            for (i in 1 until reordered.size) {
                result = execute(reordered[i], result, limit)
            }
            return result
        } else {
            if (TIMING_ENABLED) {
                logger.info("Executing JOIN - left: ${op.left.javaClass.simpleName}, right: ${op.right.javaClass.simpleName}")
            }
            val left = execute(op.left, input, NO_LIMIT)
            return execute(op.right, left, limit)
        }
    }

    /**
     * Flatten a tree of JOINs into a list of operands.
     */
    private fun flattenJoins(op: Op): List<Op> {
        return when (op) {
            is OpJoin -> flattenJoins(op.left) + flattenJoins(op.right)
            else -> listOf(op)
        }
    }

    /**
     * Reorder join operands by selectivity.
     * BGPs with constant objects are most selective and should run first.
     */
    private fun reorderJoinOperands(ops: List<Op>): List<Op> {
        return ops.sortedByDescending { estimateSelectivity(it) }
    }

    /**
     * Estimate the selectivity of an operation.
     * Higher score = more selective = should run first.
     */
    private fun estimateSelectivity(op: Op): Int {
        return when (op) {
            is OpBGP -> {
                op.pattern.list.sumOf { triple ->
                    var score = 0
                    // Constants are very selective
                    if (!triple.subject.isVariable) score += 10
                    if (!triple.predicate.isVariable) score += 5
                    if (!triple.`object`.isVariable) score += 20  // Object constants are very selective
                    score
                }
            }
            is OpJoin -> maxOf(estimateSelectivity(op.left), estimateSelectivity(op.right))
            is OpFilter -> estimateSelectivity(op.subOp) + 5  // Filters add selectivity
            else -> 0
        }
    }

    /**
     * Get a human-readable description of an operation for logging.
     */
    private fun describeOp(op: Op): String {
        return when (op) {
            is OpBGP -> "BGP(${op.pattern.list.joinToString(", ") { triple ->
                val s = if (triple.subject.isVariable) "?${triple.subject.name}" else triple.subject.toString()
                val p = if (triple.predicate.isVariable) "?${triple.predicate.name}" else (triple.predicate.localName ?: triple.predicate.toString())
                val o = when {
                    triple.`object`.isVariable -> "?${triple.`object`.name}"
                    triple.`object`.isLiteral -> "\"${triple.`object`.literalLexicalForm}\""
                    else -> triple.`object`.toString()
                }
                "$s $p $o"
            }})"
            else -> op.javaClass.simpleName
        }
    }

    /**
     * Execute a left join (OPTIONAL).
     * @param limit Maximum number of results to produce (-1 for no limit)
     */
    private fun executeLeftJoin(op: OpLeftJoin, input: QueryIterator, limit: Long = NO_LIMIT): QueryIterator {
        val leftResults = materializeBindings(execute(op.left, input, NO_LIMIT))
        val resultBindings = mutableListOf<Binding>()
        val effectiveLimit = if (limit == NO_LIMIT) Int.MAX_VALUE else limit.toInt()

        for (binding in leftResults) {
            if (resultBindings.size >= effectiveLimit) {
                if (TIMING_ENABLED) {
                    logger.info("LeftJoin early termination: reached limit of $limit")
                }
                break
            }

            val rightInput = bindingsToIterator(listOf(binding))
            val rightResults = materializeBindings(execute(op.right, rightInput, NO_LIMIT))

            if (rightResults.isNotEmpty()) {
                // Apply filter if present
                val filtered = if (op.exprs != null && !op.exprs.isEmpty) {
                    rightResults.filter { evaluateExprs(op.exprs, it) }
                } else {
                    rightResults
                }

                if (filtered.isNotEmpty()) {
                    // Add results up to the limit
                    for (result in filtered) {
                        if (resultBindings.size >= effectiveLimit) break
                        resultBindings.add(result)
                    }
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
     * @param limit Maximum number of results to produce (-1 for no limit)
     */
    private fun executeFilter(op: OpFilter, input: QueryIterator, limit: Long = NO_LIMIT): QueryIterator {
        // Note: We can't reliably push limit into sub-operation for filters
        // because filtering may reduce results significantly
        val subResults = execute(op.subOp, input, NO_LIMIT)

        // Apply each filter expression in sequence
        var result = subResults
        for (expr in op.exprs) {
            result = QueryIterFilterExpr(result, expr, execCxt)
        }

        // Apply limit after filtering if specified
        if (limit != NO_LIMIT) {
            val bindings = materializeBindings(result).take(limit.toInt())
            return bindingsToIterator(bindings)
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
     * @param limit Maximum number of results to produce (-1 for no limit)
     */
    private fun executeProject(op: OpProject, input: QueryIterator, limit: Long = NO_LIMIT): QueryIterator {
        // Project preserves result count, so we can push the limit down
        val subResults = execute(op.subOp, input, limit)
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
     * @param limit Maximum number of results to produce (-1 for no limit)
     */
    private fun executeDistinct(op: OpDistinct, input: QueryIterator, limit: Long = NO_LIMIT): QueryIterator {
        // Cannot push limit into DISTINCT's sub-operation since deduplication may reduce count
        val subResults = execute(op.subOp, input, NO_LIMIT)
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

        // Apply limit after deduplication
        val finalBindings = if (limit != NO_LIMIT) {
            distinctBindings.take(limit.toInt())
        } else {
            distinctBindings
        }

        if (TIMING_ENABLED) {
            logger.info("Distinct operation - ${bindings.size} bindings reduced to ${finalBindings.size}")
            logger.info("Distinct operation - returning iterator with ${finalBindings.size} bindings, first binding vars: ${finalBindings.firstOrNull()?.vars()?.asSequence()?.toList()}")
        }
        return bindingsToIterator(finalBindings)
    }

    /**
     * Execute a reduced operation.
     * @param limit Maximum number of results to produce (-1 for no limit)
     */
    private fun executeReduced(op: OpReduced, input: QueryIterator, limit: Long = NO_LIMIT): QueryIterator {
        // Cannot push limit into REDUCED's sub-operation since deduplication may reduce count
        val subResults = execute(op.subOp, input, NO_LIMIT)
        // REDUCED is a weaker form of DISTINCT - same implementation for correctness
        val bindings = materializeBindings(subResults)
        val distinctBindings = bindings.distinctBy { binding ->
            binding.vars().asSequence().map { v -> Pair(v, binding.get(v)) }.toSet()
        }

        // Apply limit after deduplication
        val finalBindings = if (limit != NO_LIMIT) {
            distinctBindings.take(limit.toInt())
        } else {
            distinctBindings
        }

        if (TIMING_ENABLED) {
            logger.info("Reduced operation - ${bindings.size} bindings reduced to ${finalBindings.size}")
        }
        return bindingsToIterator(finalBindings)
    }

    /**
     * Execute a slice (LIMIT/OFFSET) operation.
     * Pushes the limit down to sub-operations when possible for early termination.
     */
    private fun executeSlice(op: OpSlice, input: QueryIterator): QueryIterator {
        if (TIMING_ENABLED) {
            logger.info("Slice operation - start: ${op.start}, length: ${op.length}")
        }

        // Determine the effective limit to push down
        // We can only push down if there's no offset, or we need to account for offset
        val hasOffset = op.start > 0
        val hasLimit = op.length != Query.NOLIMIT

        if (hasLimit && !hasOffset) {
            // Simple case: LIMIT without OFFSET - push limit directly
            if (TIMING_ENABLED) {
                logger.info("Slice optimization: pushing LIMIT ${op.length} down to sub-operation")
            }
            val subResults = execute(op.subOp, input, op.length)
            // No need for QueryIterSlice since we already limited
            return subResults
        } else if (hasLimit && hasOffset) {
            // OFFSET + LIMIT case: need to fetch offset + limit rows, then skip
            val totalNeeded = op.start + op.length
            if (TIMING_ENABLED) {
                logger.info("Slice optimization: pushing combined limit ${totalNeeded} (offset=${op.start} + limit=${op.length}) down to sub-operation")
            }
            val subResults = execute(op.subOp, input, totalNeeded)
            // Apply the offset/limit
            return QueryIterSlice(subResults, op.start, op.length, execCxt)
        } else if (hasOffset && !hasLimit) {
            // Only OFFSET, no limit - cannot optimize, need all results after offset
            if (TIMING_ENABLED) {
                logger.info("Slice: OFFSET without LIMIT, cannot push down")
            }
            val subResults = execute(op.subOp, input, NO_LIMIT)
            return QueryIterSlice(subResults, op.start, op.length, execCxt)
        } else {
            // No limit or offset - just pass through
            return execute(op.subOp, input, NO_LIMIT)
        }
    }

    /**
     * Execute an order (ORDER BY) operation.
     * @param limit Maximum number of results to produce (-1 for no limit)
     */
    private fun executeOrder(op: OpOrder, input: QueryIterator, limit: Long = NO_LIMIT): QueryIterator {
        // Cannot push limit down because we need all results to sort correctly
        val subResults = execute(op.subOp, input, NO_LIMIT)
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

        // Apply limit after sorting
        val finalBindings = if (limit != NO_LIMIT) {
            sortedBindings.take(limit.toInt())
        } else {
            sortedBindings
        }

        if (TIMING_ENABLED) {
            logger.info("Order operation - sorted ${sortedBindings.size} bindings, returning ${finalBindings.size}")
        }

        return bindingsToIterator(finalBindings)
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
     * @param limit Maximum number of results to produce (-1 for no limit)
     */
    private fun executeExtend(op: OpExtend, input: QueryIterator, limit: Long = NO_LIMIT): QueryIterator {
        // BIND preserves result count, so we can push the limit down
        val subResults = execute(op.subOp, input, limit)
        return QueryIterAssign(subResults, op.varExprList, execCxt, false)
    }

    /**
     * Execute a union operation.
     * @param limit Maximum number of results to produce (-1 for no limit)
     */
    private fun executeUnion(op: OpUnion, input: QueryIterator, limit: Long = NO_LIMIT): QueryIterator {
        val inputBindings = materializeBindings(input)
        val effectiveLimit = if (limit == NO_LIMIT) Int.MAX_VALUE else limit.toInt()

        // Execute left branch first
        val leftResults = execute(op.left, bindingsToIterator(inputBindings), NO_LIMIT)
        val leftBindings = materializeBindings(leftResults)

        // If left already has enough results, we can potentially skip right branch
        if (leftBindings.size >= effectiveLimit) {
            if (TIMING_ENABLED) {
                logger.info("Union optimization: left branch returned ${leftBindings.size} results, limit is $limit - skipping right branch")
            }
            return bindingsToIterator(leftBindings.take(effectiveLimit))
        }

        // Execute right branch with remaining limit
        val remainingLimit = if (limit == NO_LIMIT) NO_LIMIT else (effectiveLimit - leftBindings.size).toLong()
        val rightResults = execute(op.right, bindingsToIterator(inputBindings), remainingLimit)
        val rightBindings = materializeBindings(rightResults)

        // Concatenate results
        val combined = leftBindings + rightBindings
        val finalBindings = if (limit != NO_LIMIT) combined.take(effectiveLimit) else combined

        return bindingsToIterator(finalBindings)
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
     * @param limit Maximum number of results to produce (-1 for no limit)
     */
    private fun executeSequence(op: OpSequence, input: QueryIterator, limit: Long = NO_LIMIT): QueryIterator {
        var current = input
        val elements = op.elements
        for (i in elements.indices) {
            // Only apply limit to the last element in the sequence
            val stepLimit = if (i == elements.size - 1) limit else NO_LIMIT
            current = execute(elements[i], current, stepLimit)
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

