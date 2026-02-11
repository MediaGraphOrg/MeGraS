package org.megras.lang.sparql.jena.batch

import org.apache.jena.graph.Node
import org.apache.jena.graph.Triple
import org.apache.jena.query.Query
import org.apache.jena.sparql.algebra.Op
import org.apache.jena.sparql.algebra.OpVars
import org.apache.jena.sparql.algebra.op.*
import org.apache.jena.sparql.core.BasicPattern
import org.apache.jena.sparql.core.Var
import org.apache.jena.sparql.engine.ExecutionContext
import org.apache.jena.sparql.engine.QueryIterator
import org.apache.jena.sparql.engine.binding.Binding
import org.apache.jena.sparql.engine.binding.BindingBuilder
import org.apache.jena.sparql.engine.iterator.*
import org.apache.jena.sparql.engine.main.QC
import org.apache.jena.sparql.expr.*
import org.apache.jena.sparql.expr.nodevalue.NodeValueString
import org.megras.data.graph.Quad
import org.megras.data.graph.StringValue
import org.megras.data.graph.QuadValue
import org.megras.graphstore.BasicQuadSet
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
        return execute(op, input, NO_LIMIT, null)
    }

    /**
     * Execute an operation with an input iterator of bindings.
     * @param limit Maximum number of results to produce (-1 for no limit)
     * @param requiredVars Set of variables that are actually needed in the result (null means all)
     */
    private fun execute(op: Op, input: QueryIterator, limit: Long = NO_LIMIT, requiredVars: Set<Var>? = null): QueryIterator {
        if (TIMING_ENABLED) {
            logger.info("Executing Op: ${op.javaClass.simpleName}" + if (limit != NO_LIMIT) " with limit $limit" else "" +
                    if (requiredVars != null) " with projection to ${requiredVars.size} vars" else "")
        }
        return when (op) {
            is OpBGP -> executeBGP(op.pattern, input, limit, requiredVars)
            is OpJoin -> executeJoin(op, input, limit, requiredVars)
            is OpLeftJoin -> executeLeftJoin(op, input, limit, requiredVars)
            is OpFilter -> executeFilter(op, input, limit, requiredVars)
            is OpProject -> executeProject(op, input, limit)
            is OpDistinct -> executeDistinct(op, input, limit, requiredVars)
            is OpReduced -> executeReduced(op, input, limit, requiredVars)
            is OpSlice -> executeSlice(op, input, requiredVars)
            is OpOrder -> executeOrder(op, input, limit, requiredVars)
            is OpGroup -> executeGroup(op, input)
            is OpExtend -> executeExtend(op, input, limit, requiredVars)
            is OpUnion -> executeUnion(op, input, limit, requiredVars)
            is OpTable -> executeTable(op, input)
            is OpSequence -> executeSequence(op, input, limit, requiredVars)
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
     * @param requiredVars Set of variables that are actually needed in the result (null means all)
     */
    private fun executeBGP(pattern: BasicPattern, input: QueryIterator, limit: Long = NO_LIMIT, requiredVars: Set<Var>? = null): QueryIterator {
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

        // Compute which variables from this BGP are needed for joins or output
        // We need: requiredVars + any vars that appear in multiple patterns (for joining)
        val effectiveRequiredVars = computeEffectiveRequiredVars(triples, requiredVars)

        if (TIMING_ENABLED) {
            logger.info("BGP starting with ${inputBindings.size} input bindings" + if (limit != NO_LIMIT) ", limit=$limit" else "")
            if (requiredVars != null) {
                logger.info("Projection pushdown: only building bindings for ${effectiveRequiredVars.size} vars: $effectiveRequiredVars")
            }
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
        for ((index, triple) in reorderedTriples.withIndex()) {
            // For intermediate patterns, we need join variables; for the last pattern, we can use final required vars
            val isLastPattern = index == reorderedTriples.size - 1
            val patternRequiredVars = if (isLastPattern) requiredVars else effectiveRequiredVars
            currentBindings = executeBatchedTriplePattern(triple, currentBindings, limit, patternRequiredVars)
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
     * Compute the effective set of required variables for BGP execution.
     * This includes: the explicitly required vars PLUS any vars needed for joins between patterns.
     */
    private fun computeEffectiveRequiredVars(triples: List<Triple>, requiredVars: Set<Var>?): Set<Var> {
        if (requiredVars == null) {
            // No projection pushdown - all vars from all patterns
            return triples.flatMap { triple ->
                listOfNotNull(
                    if (triple.subject.isVariable) Var.alloc(triple.subject) else null,
                    if (triple.predicate.isVariable) Var.alloc(triple.predicate) else null,
                    if (triple.`object`.isVariable) Var.alloc(triple.`object`) else null
                )
            }.toSet()
        }

        // Start with the explicitly required vars
        val effective = requiredVars.toMutableSet()

        // Add any vars that appear in multiple patterns (these are join variables)
        val varCounts = mutableMapOf<Var, Int>()
        for (triple in triples) {
            if (triple.subject.isVariable) {
                val v = Var.alloc(triple.subject)
                varCounts[v] = varCounts.getOrDefault(v, 0) + 1
            }
            if (triple.predicate.isVariable) {
                val v = Var.alloc(triple.predicate)
                varCounts[v] = varCounts.getOrDefault(v, 0) + 1
            }
            if (triple.`object`.isVariable) {
                val v = Var.alloc(triple.`object`)
                varCounts[v] = varCounts.getOrDefault(v, 0) + 1
            }
        }
        // Add join variables (appear more than once)
        for ((v, count) in varCounts) {
            if (count > 1) {
                effective.add(v)
            }
        }

        return effective
    }

    /**
     * Compute the set of variables required for a sub-operation, given the projected vars.
     * This includes the projected vars plus any vars needed by filters, ORDER BY, GROUP BY, etc.
     */
    private fun computeRequiredVarsForSubOp(op: Op, projectedVars: Set<Var>): Set<Var> {
        val required = projectedVars.toMutableSet()

        // Recursively collect variables used in filters, expressions, etc.
        collectExpressionVars(op, required)

        return required
    }

    /**
     * Recursively collect variables used in expressions (filters, ORDER BY, BIND, etc.)
     */
    private fun collectExpressionVars(op: Op, vars: MutableSet<Var>) {
        when (op) {
            is OpFilter -> {
                // Add variables used in filter expressions
                for (expr in op.exprs) {
                    vars.addAll(expr.varsMentioned)
                }
                collectExpressionVars(op.subOp, vars)
            }
            is OpOrder -> {
                // Add variables used in ORDER BY
                for (sortCondition in op.conditions) {
                    vars.addAll(sortCondition.expression.varsMentioned)
                }
                collectExpressionVars(op.subOp, vars)
            }
            is OpExtend -> {
                // Add variables used in BIND expressions
                for (entry in op.varExprList.exprs.entries) {
                    vars.addAll(entry.value.varsMentioned)
                }
                // Also add the variable being bound (it's needed for projection)
                vars.addAll(op.varExprList.vars)
                collectExpressionVars(op.subOp, vars)
            }
            is OpGroup -> {
                // Add GROUP BY variables and aggregation expressions
                for (v in op.groupVars.vars) {
                    vars.add(v)
                }
                for (agg in op.aggregators) {
                    vars.add(agg.`var`)
                    vars.addAll(agg.aggregator.exprList?.varsMentioned ?: emptySet())
                }
                collectExpressionVars(op.subOp, vars)
            }
            is OpProject -> {
                // Don't recurse past a nested PROJECT - it has its own projection
            }
            is OpJoin -> {
                collectExpressionVars(op.left, vars)
                collectExpressionVars(op.right, vars)
            }
            is OpLeftJoin -> {
                collectExpressionVars(op.left, vars)
                collectExpressionVars(op.right, vars)
                // Add filter expression variables if present
                op.exprs?.forEach { expr -> vars.addAll(expr.varsMentioned) }
            }
            is OpUnion -> {
                collectExpressionVars(op.left, vars)
                collectExpressionVars(op.right, vars)
            }
            is OpDistinct -> collectExpressionVars(op.subOp, vars)
            is OpReduced -> collectExpressionVars(op.subOp, vars)
            is OpSlice -> collectExpressionVars(op.subOp, vars)
            is OpSequence -> op.elements.forEach { collectExpressionVars(it, vars) }
            is OpBGP -> {
                // BGP doesn't have expressions, but we might want to add all vars
                // if they're used for joins. This is handled by computeEffectiveRequiredVars
            }
            // Other operators - no expressions to collect
            else -> {}
        }
    }

    /**
     * Execute a single triple pattern against multiple input bindings using batching.
     * Instead of N separate calls, we make one call with all possible values.
     * @param limit Maximum number of results to produce (-1 for no limit)
     * @param requiredVars Set of variables that are actually needed in the result (null means all)
     */
    private fun executeBatchedTriplePattern(triple: Triple, inputBindings: List<Binding>, limit: Long = NO_LIMIT, requiredVars: Set<Var>? = null): List<Binding> {
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
                triple, inputBindings, subjectVar, predicateVar, objectVar, effectiveLimit, startTime, requiredVars
            )
        }

        // Standard batch execution for no-limit or small input cases
        return executeBatchedTriplePatternFull(
            triple, inputBindings, subjectVar, predicateVar, objectVar, effectiveLimit, startTime, requiredVars
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
        startTime: Long,
        requiredVars: Set<Var>? = null
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

                            val newBinding = extendBinding(binding, triple, quad, requiredVars)
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

                    val newBinding = extendBinding(binding, triple, quad, requiredVars)
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
     * Now handles large subject sets by chunking instead of falling back to 'all'.
     * @param requiredVars Set of variables that are actually needed in the result (null means all)
     */
    private fun executeBatchedTriplePatternFull(
        triple: Triple,
        inputBindings: List<Binding>,
        subjectVar: Var?,
        predicateVar: Var?,
        objectVar: Var?,
        effectiveLimit: Int,
        startTime: Long,
        requiredVars: Set<Var>? = null
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

        // Check if we need chunked execution for large subject sets
        val needsChunkedSubjectExecution = hasSubjectBinding && subjectValues.size > MAX_BATCH_SIZE

        if (needsChunkedSubjectExecution) {
            return executeChunkedBySubjects(
                triple, inputBindings, subjectVar!!, subjectValues, predicateVar, objectVar,
                predicateValues, objectValues, hasPredicateBinding, hasObjectBinding,
                effectiveLimit, startTime, requiredVars
            )
        }

        // Standard execution path
        val subjectFilter: Collection<QuadValue>? = when {
            !triple.subject.isVariable -> toQuadValue(triple.subject)?.let { listOf(it) }
            hasSubjectBinding && subjectValues.isNotEmpty() -> subjectValues
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

                val newBinding = extendBinding(binding, triple, quad, requiredVars)
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
     * Execute a triple pattern with chunked subject filtering for large subject sets.
     * Instead of falling back to 'all' subjects (which fetches millions of rows),
     * this processes subjects in chunks to keep DB queries manageable.
     */
    private fun executeChunkedBySubjects(
        triple: Triple,
        inputBindings: List<Binding>,
        subjectVar: Var,
        subjectValues: Set<QuadValue>,
        predicateVar: Var?,
        objectVar: Var?,
        predicateValues: Set<QuadValue>,
        objectValues: Set<QuadValue>,
        hasPredicateBinding: Boolean,
        hasObjectBinding: Boolean,
        effectiveLimit: Int,
        startTime: Long,
        requiredVars: Set<Var>?
    ): List<Binding> {
        if (TIMING_ENABLED) {
            logger.info("Using chunked subject execution for ${subjectValues.size} subjects")
        }

        // Group input bindings by their bound subject value for efficient processing
        val bindingsBySubject: Map<QuadValue, List<Binding>> = inputBindings.groupBy { binding ->
            toQuadValue(binding.get(subjectVar))!!
        }

        val predicateFilter: Collection<QuadValue>? = when {
            !triple.predicate.isVariable -> toQuadValue(triple.predicate)?.let { listOf(it) }
            hasPredicateBinding && predicateValues.isNotEmpty() && predicateValues.size <= MAX_BATCH_SIZE -> predicateValues
            else -> null
        }

        val objectFilter: Collection<QuadValue>? = when {
            !triple.`object`.isVariable -> toQuadValue(triple.`object`)?.let { listOf(it) }
            hasObjectBinding && objectValues.isNotEmpty() && objectValues.size <= MAX_BATCH_SIZE -> objectValues
            else -> null
        }

        val results = mutableListOf<Binding>()
        val subjectList = subjectValues.toList()
        var processedChunks = 0

        // Process subjects in chunks
        for (subjectChunk in subjectList.chunked(MAX_BATCH_SIZE)) {
            if (results.size >= effectiveLimit) {
                if (TIMING_ENABLED) {
                    logger.info("Chunked execution early termination at chunk $processedChunks: reached limit of $effectiveLimit")
                }
                break
            }

            val chunkStartTime = if (TIMING_ENABLED) System.currentTimeMillis() else 0L

            if (TIMING_ENABLED) {
                logger.info("Processing subject chunk ${processedChunks + 1}: ${subjectChunk.size} subjects")
            }

            val matchingQuads = quadSet.filter(subjectChunk, predicateFilter, objectFilter)

            if (TIMING_ENABLED) {
                logger.info("Chunk query returned ${matchingQuads.size} quads in ${System.currentTimeMillis() - chunkStartTime}ms")
            }

            // Build index for this chunk
            val quadIndex = buildQuadIndex(matchingQuads)

            // Process bindings for subjects in this chunk
            for (subject in subjectChunk) {
                if (results.size >= effectiveLimit) break

                val bindingsForSubject = bindingsBySubject[subject] ?: continue
                val quadsForSubject = quadIndex.bySubject[subject] ?: continue

                for (binding in bindingsForSubject) {
                    if (results.size >= effectiveLimit) break

                    for (quad in quadsForSubject) {
                        if (results.size >= effectiveLimit) break

                        // Verify compatibility (predicate/object matching)
                        if (!isQuadCompatible(triple, binding, quad)) continue

                        val newBinding = extendBinding(binding, triple, quad, requiredVars)
                        if (newBinding != null) {
                            results.add(newBinding)
                        }
                    }
                }
            }

            processedChunks++
        }

        if (TIMING_ENABLED) {
            logger.info("Chunked subject execution completed: ${results.size} bindings from ${processedChunks} chunks in ${System.currentTimeMillis() - startTime}ms")
        }

        return results
    }

    /**
     * Check if a quad is compatible with the current binding (predicate and object matching).
     */
    private fun isQuadCompatible(triple: Triple, binding: Binding, quad: Quad): Boolean {
        // Check predicate
        if (!triple.predicate.isVariable) {
            val expectedPredicate = toQuadValue(triple.predicate)
            if (quad.predicate != expectedPredicate) return false
        } else {
            val predicateVar = Var.alloc(triple.predicate)
            if (binding.contains(predicateVar)) {
                val expectedPredicate = toQuadValue(binding.get(predicateVar))
                if (quad.predicate != expectedPredicate) return false
            }
        }

        // Check object
        if (!triple.`object`.isVariable) {
            val expectedObject = toQuadValue(triple.`object`)
            if (quad.`object` != expectedObject) return false
        } else {
            val objectVar = Var.alloc(triple.`object`)
            if (binding.contains(objectVar)) {
                val expectedObject = toQuadValue(binding.get(objectVar))
                if (quad.`object` != expectedObject) return false
            }
        }

        return true
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
     * @param requiredVars Set of variables to actually bind (null means all)
     */
    private fun extendBinding(binding: Binding, triple: Triple, quad: Quad, requiredVars: Set<Var>? = null): Binding? {
        val builder = BindingBuilder.create(binding)

        // Check and bind subject
        if (!bindNode(builder, triple.subject, quad.subject, binding, requiredVars)) {
            return null
        }

        // Check and bind predicate
        if (!bindNode(builder, triple.predicate, quad.predicate, binding, requiredVars)) {
            return null
        }

        // Check and bind object
        if (!bindNode(builder, triple.`object`, quad.`object`, binding, requiredVars)) {
            return null
        }

        return builder.build()
    }

    /**
     * Bind a node from a triple pattern to a value from a quad.
     * Returns false if there's a conflict with an existing binding.
     * @param requiredVars Set of variables to actually bind (null means all)
     */
    private fun bindNode(builder: BindingBuilder, patternNode: Node, quadValue: QuadValue, existingBinding: Binding, requiredVars: Set<Var>? = null): Boolean {
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
                // Only bind the variable if it's in the required set (or if no projection pushdown)
                if (requiredVars == null || variable in requiredVars) {
                    builder.add(variable, quadNode)
                }
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
     * @param requiredVars Set of variables that are actually needed in the result (null means all)
     */
    private fun executeJoin(op: OpJoin, input: QueryIterator, limit: Long = NO_LIMIT, requiredVars: Set<Var>? = null): QueryIterator {
        // Flatten nested joins to allow global reordering
        val allOps = flattenJoins(op)

        // For joins, we need to compute which vars are needed for joining plus the required vars
        val joinRequiredVars = computeJoinRequiredVars(allOps, requiredVars)

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
            var result = execute(reordered[0], input, NO_LIMIT, joinRequiredVars)

            // Subsequent operands: propagate limit to ALL of them
            // Each step can potentially early-terminate once we have enough joined results
            for (i in 1 until reordered.size) {
                result = execute(reordered[i], result, limit, joinRequiredVars)
            }
            return result
        } else {
            if (TIMING_ENABLED) {
                logger.info("Executing JOIN - left: ${op.left.javaClass.simpleName}, right: ${op.right.javaClass.simpleName}")
            }
            val left = execute(op.left, input, NO_LIMIT, joinRequiredVars)
            return execute(op.right, left, limit, joinRequiredVars)
        }
    }

    /**
     * Compute required vars for a join, including join variables.
     */
    private fun computeJoinRequiredVars(ops: List<Op>, requiredVars: Set<Var>?): Set<Var>? {
        if (requiredVars == null) return null

        val joinVars = mutableSetOf<Var>()
        val allVars = mutableMapOf<Var, Int>()

        // Collect all variables from all operands and count occurrences
        for (op in ops) {
            val opVars = OpVars.visibleVars(op)
            for (v in opVars) {
                allVars[v] = allVars.getOrDefault(v, 0) + 1
            }
        }

        // Variables that appear in multiple operands are join variables
        for ((v, count) in allVars) {
            if (count > 1) {
                joinVars.add(v)
            }
        }

        // Required = explicitly required + join variables
        return requiredVars + joinVars
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
     * @param requiredVars Set of variables that are actually needed in the result (null means all)
     */
    private fun executeLeftJoin(op: OpLeftJoin, input: QueryIterator, limit: Long = NO_LIMIT, requiredVars: Set<Var>? = null): QueryIterator {
        // For left join, we need vars from both sides plus filter vars
        val leftJoinRequiredVars = if (requiredVars != null) {
            val leftVars = OpVars.visibleVars(op.left)
            val rightVars = OpVars.visibleVars(op.right)
            val joinVars = leftVars.intersect(rightVars.toSet())
            val filterVars = op.exprs?.flatMap { it.varsMentioned }?.toSet() ?: emptySet()
            requiredVars + joinVars + filterVars
        } else null

        val leftResults = materializeBindings(execute(op.left, input, NO_LIMIT, leftJoinRequiredVars))
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
            val rightResults = materializeBindings(execute(op.right, rightInput, NO_LIMIT, leftJoinRequiredVars))

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
     * Represents a detected text filter that can be pushed down to the database.
     * @param variable The variable being filtered (e.g., ?ocr)
     * @param searchText The text to search for
     * @param originalExpr The original expression (for removal from filter list)
     */
    private data class TextFilterInfo(
        val variable: Var,
        val searchText: String,
        val originalExpr: Expr
    )

    /**
     * Try to extract a pushable text filter from a CONTAINS expression.
     * Supports patterns like:
     * - CONTAINS(?var, "text")
     * - CONTAINS(LCASE(?var), LCASE("text"))
     * - CONTAINS(STR(?var), "text")
     * - CONTAINS(LCASE(STR(?var)), LCASE("text"))
     */
    private fun tryExtractTextFilter(expr: Expr): TextFilterInfo? {
        if (expr !is E_StrContains) return null

        val arg1 = expr.arg1
        val arg2 = expr.arg2

        // Extract the variable from arg1 (may be wrapped in LCASE, STR, or both)
        val variable = extractVariableFromExpr(arg1) ?: return null

        // Extract the search text from arg2 (may be wrapped in LCASE)
        val searchText = extractStringFromExpr(arg2) ?: return null

        return TextFilterInfo(variable, searchText, expr)
    }

    /**
     * Extract a variable from an expression, unwrapping LCASE and STR if present.
     */
    private fun extractVariableFromExpr(expr: Expr): Var? {
        return when (expr) {
            is ExprVar -> expr.asVar()
            is E_StrLowerCase -> extractVariableFromExpr(expr.arg)
            is E_Str -> extractVariableFromExpr(expr.arg)
            else -> null
        }
    }

    /**
     * Extract a string literal from an expression, unwrapping LCASE if present.
     */
    private fun extractStringFromExpr(expr: Expr): String? {
        return when (expr) {
            is NodeValueString -> expr.asString()
            is E_StrLowerCase -> extractStringFromExpr(expr.arg)
            is NodeValue -> if (expr.isString) expr.asString() else null
            else -> null
        }
    }

    /**
     * Find the triple pattern in a BGP that binds a specific variable in object position.
     * Returns the predicate if found.
     */
    private fun findPredicateForObjectVariable(bgp: OpBGP, variable: Var): QuadValue? {
        for (triple in bgp.pattern.list) {
            if (triple.`object`.isVariable && Var.alloc(triple.`object`) == variable) {
                // Found the pattern where this variable is bound as the object
                if (!triple.predicate.isVariable) {
                    return toQuadValue(triple.predicate)
                }
            }
        }
        return null
    }

    /**
     * Execute a filter operation with text filter pushdown optimization.
     * @param limit Maximum number of results to produce (-1 for no limit)
     * @param requiredVars Set of variables that are actually needed in the result (null means all)
     */
    private fun executeFilter(op: OpFilter, input: QueryIterator, limit: Long = NO_LIMIT, requiredVars: Set<Var>? = null): QueryIterator {
        // For filters, we need the required vars plus any vars used in filter expressions
        val filterRequiredVars = if (requiredVars != null) {
            val filterVars = op.exprs.flatMap { it.varsMentioned }.toSet()
            requiredVars + filterVars
        } else null

        // Try to detect pushable text filters
        val textFilters = mutableListOf<TextFilterInfo>()
        val remainingExprs = mutableListOf<Expr>()

        for (expr in op.exprs) {
            val textFilter = tryExtractTextFilter(expr)
            if (textFilter != null) {
                textFilters.add(textFilter)
            } else {
                remainingExprs.add(expr)
            }
        }

        // If we found pushable text filters and the sub-op is a BGP, try to push down
        if (textFilters.isNotEmpty() && op.subOp is OpBGP) {
            val bgp = op.subOp as OpBGP

            // For each text filter, find the corresponding predicate and apply textFilter
            val pushedFilters = mutableListOf<TextFilterInfo>()
            val predicateTextFilters = mutableMapOf<QuadValue, MutableList<TextFilterInfo>>()

            for (tf in textFilters) {
                val predicate = findPredicateForObjectVariable(bgp, tf.variable)
                if (predicate != null) {
                    predicateTextFilters.getOrPut(predicate) { mutableListOf() }.add(tf)
                    pushedFilters.add(tf)
                    if (TIMING_ENABLED) {
                        logger.info("Text filter pushdown: CONTAINS on ${tf.variable} with text '${tf.searchText}' -> predicate $predicate")
                    }
                } else {
                    // Can't push this filter, add to remaining
                    remainingExprs.add(tf.originalExpr)
                }
            }

            if (pushedFilters.isNotEmpty()) {
                // Execute BGP with text filter pushdown
                val subResults = executeBGPWithTextFilters(bgp, input, predicateTextFilters, filterRequiredVars)

                // Apply remaining filters
                var result = subResults
                for (expr in remainingExprs) {
                    result = QueryIterFilterExpr(result, expr, execCxt)
                }

                // Apply limit after filtering if specified
                if (limit != NO_LIMIT) {
                    val bindings = materializeBindings(result).take(limit.toInt())
                    return bindingsToIterator(bindings)
                }

                return result
            }
        }

        // No text filter pushdown - use standard execution
        val subResults = execute(op.subOp, input, NO_LIMIT, filterRequiredVars)

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
     * Execute a BGP with text filter pushdown.
     * For patterns that match text filters, use quadSet.textFilter() instead of regular filter().
     */
    private fun executeBGPWithTextFilters(
        bgp: OpBGP,
        input: QueryIterator,
        predicateTextFilters: Map<QuadValue, List<TextFilterInfo>>,
        requiredVars: Set<Var>?
    ): QueryIterator {
        val startTime = if (TIMING_ENABLED) System.currentTimeMillis() else 0L

        val triples = bgp.pattern.list
        if (triples.isEmpty()) {
            return input
        }

        val inputBindings = materializeBindings(input)
        if (inputBindings.isEmpty()) {
            return QueryIterNullIterator.create(execCxt)
        }

        val effectiveRequiredVars = computeEffectiveRequiredVars(triples, requiredVars)

        if (TIMING_ENABLED) {
            logger.info("BGP with text filters starting with ${inputBindings.size} input bindings")
        }

        // Reorder patterns, but prioritize patterns with text filters
        val reorderedTriples = reorderPatternsWithTextFilters(triples, inputBindings.firstOrNull(), predicateTextFilters)

        if (TIMING_ENABLED) {
            logger.info("Reordered patterns (with text filter priority): ${reorderedTriples.map { "${it.subject} ${it.predicate} ${it.`object`}" }}")
        }

        var currentBindings = inputBindings

        for ((index, triple) in reorderedTriples.withIndex()) {
            val isLastPattern = index == reorderedTriples.size - 1
            val patternRequiredVars = if (isLastPattern) requiredVars else effectiveRequiredVars

            // Check if this pattern has a text filter
            val predicate = if (!triple.predicate.isVariable) toQuadValue(triple.predicate) else null
            val textFiltersForPattern = if (predicate != null) predicateTextFilters[predicate] else null

            if (textFiltersForPattern != null && triple.`object`.isVariable) {
                // Execute with text filter pushdown
                currentBindings = executeBatchedTriplePatternWithTextFilter(
                    triple, currentBindings, textFiltersForPattern, patternRequiredVars
                )
            } else {
                // Standard execution
                currentBindings = executeBatchedTriplePattern(triple, currentBindings, NO_LIMIT, patternRequiredVars)
            }

            if (currentBindings.isEmpty()) {
                break
            }
        }

        if (TIMING_ENABLED) {
            logger.info("BGP with text filters execution time: ${System.currentTimeMillis() - startTime}ms, results: ${currentBindings.size}")
        }

        return bindingsToIterator(currentBindings)
    }

    /**
     * Reorder patterns, giving priority to patterns with text filters (they are very selective).
     */
    private fun reorderPatternsWithTextFilters(
        triples: List<Triple>,
        currentBinding: Binding?,
        predicateTextFilters: Map<QuadValue, List<TextFilterInfo>>
    ): List<Triple> {
        if (triples.size <= 1) return triples

        fun selectivityScore(triple: Triple): Int {
            var score = 0

            // Check if this pattern has a text filter - very selective!
            val predicate = if (!triple.predicate.isVariable) toQuadValue(triple.predicate) else null
            if (predicate != null && predicateTextFilters.containsKey(predicate)) {
                score += 100 // Text filters are extremely selective
            }

            // Standard selectivity scoring
            if (!triple.subject.isVariable) score += 10
            if (!triple.predicate.isVariable) score += 5
            if (!triple.`object`.isVariable) score += 20

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
     * Execute a triple pattern with text filter pushdown.
     * Uses quadSet.textFilter() for efficient database-level text search.
     */
    private fun executeBatchedTriplePatternWithTextFilter(
        triple: Triple,
        inputBindings: List<Binding>,
        textFilters: List<TextFilterInfo>,
        requiredVars: Set<Var>?
    ): List<Binding> {
        val startTime = if (TIMING_ENABLED) System.currentTimeMillis() else 0L

        val predicate = toQuadValue(triple.predicate) ?: return emptyList()
        val subjectVar = if (triple.subject.isVariable) Var.alloc(triple.subject) else null
        val objectVar = if (triple.`object`.isVariable) Var.alloc(triple.`object`) else null

        if (TIMING_ENABLED) {
            logger.info("Executing triple pattern with text filter: ${triple.subject} ${triple.predicate} ${triple.`object`}")
            logger.info("Text filters: ${textFilters.map { it.searchText }}")
        }

        // Use the first text filter for initial database query
        // (multiple text filters on same predicate are rare, but we handle them with in-memory filtering)
        val primaryTextFilter = textFilters.first()
        var matchingQuads = quadSet.textFilter(predicate, primaryTextFilter.searchText)

        if (TIMING_ENABLED) {
            logger.info("textFilter returned ${matchingQuads.size} quads in ${System.currentTimeMillis() - startTime}ms")
        }

        // Apply additional text filters if any (in-memory)
        if (textFilters.size > 1) {
            for (i in 1 until textFilters.size) {
                val additionalFilter = textFilters[i]
                matchingQuads = BasicQuadSet(matchingQuads.filter { quad ->
                    val objValue = quad.`object`
                    objValue is StringValue && objValue.value.contains(additionalFilter.searchText, ignoreCase = true)
                }.toSet())
            }
            if (TIMING_ENABLED) {
                logger.info("After additional text filters: ${matchingQuads.size} quads")
            }
        }

        // Now filter by subject bindings if we have them
        val subjectValues = if (subjectVar != null) {
            inputBindings.mapNotNull { binding ->
                if (binding.contains(subjectVar)) toQuadValue(binding.get(subjectVar)) else null
            }.toSet()
        } else {
            // Subject is a constant
            val constantSubject = toQuadValue(triple.subject)
            if (constantSubject != null) setOf(constantSubject) else emptySet()
        }

        // Filter quads by subject if we have subject constraints
        val filteredQuads = if (subjectValues.isNotEmpty()) {
            matchingQuads.filter { it.subject in subjectValues }
        } else {
            matchingQuads.toList()
        }

        if (TIMING_ENABLED) {
            logger.info("After subject filtering: ${filteredQuads.size} quads (from ${subjectValues.size} subject values)")
        }

        // Build index for joining
        val quadIndex = buildQuadIndex(BasicQuadSet(filteredQuads.toSet()))

        val results = mutableListOf<Binding>()

        for (binding in inputBindings) {
            val compatibleQuads = findCompatibleQuads(triple, binding, quadIndex)

            for (quad in compatibleQuads) {
                val newBinding = extendBinding(binding, triple, quad, requiredVars)
                if (newBinding != null) {
                    results.add(newBinding)
                }
            }
        }

        if (TIMING_ENABLED) {
            logger.info("Triple pattern with text filter join produced ${results.size} bindings in ${System.currentTimeMillis() - startTime}ms")
        }

        return results
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
     * Implements projection pushdown - only fetches variables that are actually needed.
     * @param limit Maximum number of results to produce (-1 for no limit)
     */
    private fun executeProject(op: OpProject, input: QueryIterator, limit: Long = NO_LIMIT): QueryIterator {
        // Compute the set of required variables for the sub-operation
        // This includes: projected vars + any vars needed for filters/expressions in the sub-tree
        val projectedVars = op.vars.toSet()
        val requiredVars = computeRequiredVarsForSubOp(op.subOp, projectedVars)

        if (TIMING_ENABLED) {
            logger.info("Project operation - projecting to vars: ${op.vars}")
            logger.info("Projection pushdown - required vars for sub-op: $requiredVars")
        }

        // Project preserves result count, so we can push the limit down
        // Also push down the required variables to avoid fetching unnecessary data
        val subResults = execute(op.subOp, input, limit, requiredVars)

        // Materialize and manually project to ensure all bindings are processed correctly
        val bindings = materializeBindings(subResults)
        if (TIMING_ENABLED) {
            logger.info("Project operation - ${bindings.size} bindings to project")
        }

        // If projection was already pushed down, bindings should already have only required vars
        // But we still project to ensure only projected vars are in the final result
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
     * @param requiredVars Set of variables that are actually needed in the result (null means all)
     */
    private fun executeDistinct(op: OpDistinct, input: QueryIterator, limit: Long = NO_LIMIT, requiredVars: Set<Var>? = null): QueryIterator {
        // Cannot push limit into DISTINCT's sub-operation since deduplication may reduce count
        // Propagate requiredVars to sub-operation
        val subResults = execute(op.subOp, input, NO_LIMIT, requiredVars)
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
     * @param requiredVars Set of variables that are actually needed in the result (null means all)
     */
    private fun executeReduced(op: OpReduced, input: QueryIterator, limit: Long = NO_LIMIT, requiredVars: Set<Var>? = null): QueryIterator {
        // Cannot push limit into REDUCED's sub-operation since deduplication may reduce count
        // Propagate requiredVars to sub-operation
        val subResults = execute(op.subOp, input, NO_LIMIT, requiredVars)
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
     * @param requiredVars Set of variables that are actually needed in the result (null means all)
     */
    private fun executeSlice(op: OpSlice, input: QueryIterator, requiredVars: Set<Var>? = null): QueryIterator {
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
            val subResults = execute(op.subOp, input, op.length, requiredVars)
            // No need for QueryIterSlice since we already limited
            return subResults
        } else if (hasLimit && hasOffset) {
            // OFFSET + LIMIT case: need to fetch offset + limit rows, then skip
            val totalNeeded = op.start + op.length
            if (TIMING_ENABLED) {
                logger.info("Slice optimization: pushing combined limit ${totalNeeded} (offset=${op.start} + limit=${op.length}) down to sub-operation")
            }
            val subResults = execute(op.subOp, input, totalNeeded, requiredVars)
            // Apply the offset/limit
            return QueryIterSlice(subResults, op.start, op.length, execCxt)
        } else if (hasOffset && !hasLimit) {
            // Only OFFSET, no limit - cannot optimize, need all results after offset
            if (TIMING_ENABLED) {
                logger.info("Slice: OFFSET without LIMIT, cannot push down")
            }
            val subResults = execute(op.subOp, input, NO_LIMIT, requiredVars)
            return QueryIterSlice(subResults, op.start, op.length, execCxt)
        } else {
            // No limit or offset - just pass through
            return execute(op.subOp, input, NO_LIMIT, requiredVars)
        }
    }

    /**
     * Execute an order (ORDER BY) operation.
     * @param limit Maximum number of results to produce (-1 for no limit)
     * @param requiredVars Set of variables that are actually needed in the result (null means all)
     */
    private fun executeOrder(op: OpOrder, input: QueryIterator, limit: Long = NO_LIMIT, requiredVars: Set<Var>? = null): QueryIterator {
        // For ORDER BY, we need required vars plus any vars used in sort conditions
        val orderRequiredVars = if (requiredVars != null) {
            val sortVars = op.conditions.flatMap { it.expression.varsMentioned }.toSet()
            requiredVars + sortVars
        } else null

        // Cannot push limit down because we need all results to sort correctly
        val subResults = execute(op.subOp, input, NO_LIMIT, orderRequiredVars)
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
        // GROUP BY needs all vars for grouping and aggregation, so don't push projection
        val subResults = execute(op.subOp, input, NO_LIMIT, null)
        // Delegate to Jena's default execution for complex GROUP BY
        return QC.execute(op, subResults, execCxt)
    }

    /**
     * Execute an extend (BIND) operation.
     * @param limit Maximum number of results to produce (-1 for no limit)
     * @param requiredVars Set of variables that are actually needed in the result (null means all)
     */
    private fun executeExtend(op: OpExtend, input: QueryIterator, limit: Long = NO_LIMIT, requiredVars: Set<Var>? = null): QueryIterator {
        // For BIND, we need required vars plus any vars used in the BIND expressions
        val extendRequiredVars = if (requiredVars != null) {
            val exprVars = op.varExprList.exprs.values.flatMap { it.varsMentioned }.toSet()
            requiredVars + exprVars
        } else null

        // BIND preserves result count, so we can push the limit down
        val subResults = execute(op.subOp, input, limit, extendRequiredVars)
        return QueryIterAssign(subResults, op.varExprList, execCxt, false)
    }

    /**
     * Execute a union operation.
     * @param limit Maximum number of results to produce (-1 for no limit)
     * @param requiredVars Set of variables that are actually needed in the result (null means all)
     */
    private fun executeUnion(op: OpUnion, input: QueryIterator, limit: Long = NO_LIMIT, requiredVars: Set<Var>? = null): QueryIterator {
        val inputBindings = materializeBindings(input)
        val effectiveLimit = if (limit == NO_LIMIT) Int.MAX_VALUE else limit.toInt()

        // Execute left branch first - propagate requiredVars
        val leftResults = execute(op.left, bindingsToIterator(inputBindings), NO_LIMIT, requiredVars)
        val leftBindings = materializeBindings(leftResults)

        // If left already has enough results, we can potentially skip right branch
        if (leftBindings.size >= effectiveLimit) {
            if (TIMING_ENABLED) {
                logger.info("Union optimization: left branch returned ${leftBindings.size} results, limit is $limit - skipping right branch")
            }
            return bindingsToIterator(leftBindings.take(effectiveLimit))
        }

        // Execute right branch with remaining limit - propagate requiredVars
        val remainingLimit = if (limit == NO_LIMIT) NO_LIMIT else (effectiveLimit - leftBindings.size).toLong()
        val rightResults = execute(op.right, bindingsToIterator(inputBindings), remainingLimit, requiredVars)
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
     * @param requiredVars Set of variables that are actually needed in the result (null means all)
     */
    private fun executeSequence(op: OpSequence, input: QueryIterator, limit: Long = NO_LIMIT, requiredVars: Set<Var>? = null): QueryIterator {
        var current = input
        val elements = op.elements
        for (i in elements.indices) {
            // Only apply limit to the last element in the sequence
            val stepLimit = if (i == elements.size - 1) limit else NO_LIMIT
            // Propagate requiredVars to all elements
            current = execute(elements[i], current, stepLimit, requiredVars)
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

