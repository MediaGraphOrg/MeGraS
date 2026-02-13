package org.megras.benchmark

import org.junit.jupiter.api.Test

/**
 * LSC SPARQL Benchmark
 *
 * Benchmarks SPARQL queries for the Lifelog Search Challenge (LSC) dataset.
 */
class LscSparqlBenchmark {

    private val config = BenchmarkConfig(
        name = "LSC SPARQL Benchmark",
        queriesDir = "src/test/resources/lsc_sparql_queries",
        reportsDir = "benchmark_reports/lsc"
    )

    @Test
    fun run() {
        try {
            SparqlBenchmarkRunner(config).runBenchmark()
        } catch (e: IllegalStateException) {
            println("ERROR: ${e.message}")
        }
    }
}
