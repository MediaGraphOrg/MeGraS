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
import org.slf4j.LoggerFactory

/**
 * Factory that creates BatchingQueryEngine instances for queries against JenaGraphWrapper.
 * This enables result set batching to solve the N+1 database call problem.
 */
class BatchingQueryEngineFactory : QueryEngineFactory {

    companion object {
        private val logger = LoggerFactory.getLogger(BatchingQueryEngineFactory::class.java)
        private var registeredInstance: BatchingQueryEngineFactory? = null

        /**
         * Registers this factory with the Jena QueryEngineRegistry.
         * Should be called once at application startup.
         */
        @JvmStatic
        fun register() {
            if (registeredInstance == null) {
                val instance = BatchingQueryEngineFactory()
                QueryEngineRegistry.addFactory(instance)
                registeredInstance = instance
                logger.info("BatchingQueryEngineFactory REGISTERED with Jena QueryEngineRegistry")
            } else {
                logger.debug("BatchingQueryEngineFactory already registered, skipping")
            }
        }

        /**
         * Unregisters this factory from the Jena QueryEngineRegistry.
         */
        @JvmStatic
        fun unregister() {
            registeredInstance?.let { instance ->
                QueryEngineRegistry.removeFactory(instance)
                registeredInstance = null
                logger.info("BatchingQueryEngineFactory UNREGISTERED from Jena QueryEngineRegistry")
            } ?: run {
                logger.debug("BatchingQueryEngineFactory was not registered, nothing to unregister")
            }
        }

        /**
         * Returns true if the factory is currently registered.
         */
        @JvmStatic
        fun isRegistered(): Boolean = registeredInstance != null
    }

    /**
     * Determines if this factory can handle the given query against the dataset.
     * We accept queries if the default graph is a JenaGraphWrapper.
     */
    override fun accept(query: Query?, datasetGraph: DatasetGraph?, context: Context?): Boolean {
        val accepted = datasetGraph?.defaultGraph is JenaGraphWrapper
        if (accepted) {
            logger.debug("BatchingQueryEngineFactory ACCEPTING query")
        }
        return accepted
    }

    /**
     * Determines if this factory can handle the given algebra operation.
     */
    override fun accept(op: Op?, datasetGraph: DatasetGraph?, context: Context?): Boolean {
        val accepted = datasetGraph?.defaultGraph is JenaGraphWrapper
        if (accepted) {
            logger.debug("BatchingQueryEngineFactory ACCEPTING algebra op")
        }
        return accepted
    }

    /**
     * Creates a query plan from a Query object.
     */
    override fun create(query: Query, datasetGraph: DatasetGraph, initialBinding: Binding?, context: Context?): Plan {
        logger.info("BatchingQueryEngineFactory CREATING query plan (using BATCHING engine)")
        val engine = BatchingQueryEngine.create(query, datasetGraph, initialBinding, context)
        return engine.getPlan()
    }

    /**
     * Creates a query plan from an algebra Op.
     */
    override fun create(op: Op, datasetGraph: DatasetGraph, initialBinding: Binding?, context: Context?): Plan {
        logger.info("BatchingQueryEngineFactory CREATING query plan from Op (using BATCHING engine)")
        val engine = BatchingQueryEngine.create(op, datasetGraph, initialBinding, context)
        return engine.getPlan()
    }
}

