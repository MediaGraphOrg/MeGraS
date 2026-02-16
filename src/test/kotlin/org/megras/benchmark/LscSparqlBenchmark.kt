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
        baseUrl = "http://localhost:8080/query/sparql",
        queriesDir = "src/test/resources/lsc_sparql_queries",
        reportsDir = "benchmark_reports/lsc",
        warmupRuns = 3,
        warmRuns = 10,
        connectTimeoutMs = 30000,
        readTimeoutMs = 60000
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
