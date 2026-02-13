package org.megras.benchmark

import org.junit.jupiter.api.Test

/**
 * LSC SPARQL Benchmark Test
 *
 * Benchmarks SPARQL queries for the Lifelog Search Challenge (LSC) dataset.
 *
 * @see BenchmarkConfig.lsc for configuration
 * @see SparqlBenchmarkRunner for the benchmark implementation
 */
class LscSparqlBenchmark {

    @Test
    fun runLscSparqlBenchmark() {
        val config = BenchmarkConfig.lsc()
        val runner = SparqlBenchmarkRunner(config)

        try {
            runner.runBenchmark()
        } catch (e: IllegalStateException) {
            println("ERROR: ${e.message}")
        }
    }
}
