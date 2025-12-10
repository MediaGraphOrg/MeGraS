package org.megras.lang.sparql.jena.batch

import org.apache.jena.query.Query
import org.apache.jena.sparql.algebra.Op
import org.apache.jena.sparql.core.DatasetGraph
import org.apache.jena.sparql.engine.QueryEngineBase
import org.apache.jena.sparql.engine.binding.Binding
import org.apache.jena.sparql.util.Context
import org.megras.graphstore.QuadSet
import org.megras.lang.sparql.jena.JenaGraphWrapper
import org.slf4j.LoggerFactory

/**
 * Custom Jena Query Engine that implements result set batching for BGP execution.
 * This replaces Jena's default sequential pattern-by-pattern execution with a bulk
 * strategy to eliminate the N+1 database call problem.
 */
class BatchingQueryEngine : QueryEngineBase {

    private val quadSet: QuadSet

    companion object {
        private val logger = LoggerFactory.getLogger(BatchingQueryEngine::class.java)

        /**
         * Factory method to create a BatchingQueryEngine from a Query
         */
        fun create(query: Query, datasetGraph: DatasetGraph, initialBinding: Binding?, context: Context?): BatchingQueryEngine {
            return BatchingQueryEngine(query, datasetGraph, initialBinding, context)
        }

        /**
         * Factory method to create a BatchingQueryEngine from an Op
         */
        fun create(op: Op, datasetGraph: DatasetGraph, initialBinding: Binding?, context: Context?): BatchingQueryEngine {
            return BatchingQueryEngine(op, datasetGraph, initialBinding, context)
        }
    }

    /**
     * Constructor from Query
     */
    private constructor(query: Query, datasetGraph: DatasetGraph, initialBinding: Binding?, context: Context?)
            : super(query, datasetGraph, initialBinding, context) {
        val wrapper = datasetGraph.defaultGraph as JenaGraphWrapper
        this.quadSet = wrapper.getQuadSet()
    }

    /**
     * Constructor from Op (algebra)
     */
    private constructor(op: Op, datasetGraph: DatasetGraph, initialBinding: Binding?, context: Context?)
            : super(op, datasetGraph, initialBinding, context) {
        val wrapper = datasetGraph.defaultGraph as JenaGraphWrapper
        this.quadSet = wrapper.getQuadSet()
    }

    /**
     * Returns our custom OpExecutor factory which performs batched execution.
     */
    override fun modifyOp(op: Op): Op {
        // We can optimize/transform the op here if needed
        // For now, return as-is; the actual batching happens in the OpExecutor
        return op
    }

    /**
     * Create and evaluate the query plan.
     * Uses our custom BatchingOpExecutor for efficient batched execution.
     */
    override fun eval(op: Op, datasetGraph: DatasetGraph, binding: Binding, context: Context): org.apache.jena.sparql.engine.QueryIterator {
        //logger.info("BatchingQueryEngine.eval() called with op: ${op.javaClass.simpleName}")

        val execCxt = createExecutionContext(datasetGraph, context, binding)

        // Create our batching executor and evaluate the operation
        val executor = BatchingOpExecutor(execCxt, quadSet)
        val result = executor.executeOp(op, binding)

        //logger.info("BatchingQueryEngine.eval() returning iterator: ${result.javaClass.simpleName}")

        return result
    }

    /**
     * Creates the execution context with our custom settings.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun createExecutionContext(
        datasetGraph: DatasetGraph,
        context: Context,
        binding: Binding
    ): org.apache.jena.sparql.engine.ExecutionContext {
        return org.apache.jena.sparql.engine.ExecutionContext(
            context,
            datasetGraph.defaultGraph,
            datasetGraph,
            org.apache.jena.sparql.engine.main.QC.getFactory(context)
        )
    }
}

