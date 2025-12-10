package org.megras.lang.sparql.jena.batch

import org.apache.jena.query.Query
import org.apache.jena.sparql.algebra.Op
import org.apache.jena.sparql.core.DatasetGraph
import org.apache.jena.sparql.engine.Plan
import org.apache.jena.sparql.engine.QueryEngineFactory
import org.apache.jena.sparql.engine.QueryEngineRegistry
import org.apache.jena.sparql.engine.binding.Binding
import org.apache.jena.sparql.util.Context
import org.megras.lang.sparql.jena.JenaGraphWrapper

/**
 * Factory that creates BatchingQueryEngine instances for queries against JenaGraphWrapper.
 * This enables result set batching to solve the N+1 database call problem.
 */
class BatchingQueryEngineFactory : QueryEngineFactory {

    companion object {
        private var registered = false

        /**
         * Registers this factory with the Jena QueryEngineRegistry.
         * Should be called once at application startup.
         */
        @JvmStatic
        fun register() {
            if (!registered) {
                QueryEngineRegistry.addFactory(BatchingQueryEngineFactory())
                registered = true
            }
        }

        /**
         * Unregisters this factory from the Jena QueryEngineRegistry.
         */
        @JvmStatic
        fun unregister() {
            QueryEngineRegistry.removeFactory(BatchingQueryEngineFactory())
            registered = false
        }
    }

    /**
     * Determines if this factory can handle the given query against the dataset.
     * We accept queries if the default graph is a JenaGraphWrapper.
     */
    override fun accept(query: Query?, datasetGraph: DatasetGraph?, context: Context?): Boolean {
        return datasetGraph?.defaultGraph is JenaGraphWrapper
    }

    /**
     * Determines if this factory can handle the given algebra operation.
     */
    override fun accept(op: Op?, datasetGraph: DatasetGraph?, context: Context?): Boolean {
        return datasetGraph?.defaultGraph is JenaGraphWrapper
    }

    /**
     * Creates a query plan from a Query object.
     */
    override fun create(query: Query, datasetGraph: DatasetGraph, initialBinding: Binding?, context: Context?): Plan {
        val engine = BatchingQueryEngine.create(query, datasetGraph, initialBinding, context)
        return engine.getPlan()
    }

    /**
     * Creates a query plan from an algebra Op.
     */
    override fun create(op: Op, datasetGraph: DatasetGraph, initialBinding: Binding?, context: Context?): Plan {
        val engine = BatchingQueryEngine.create(op, datasetGraph, initialBinding, context)
        return engine.getPlan()
    }
}

