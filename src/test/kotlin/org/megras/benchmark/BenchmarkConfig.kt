package org.megras.benchmark

/**
 * Configuration for a SPARQL benchmark.
 *
 * This data class holds all configurable parameters for running a benchmark,
 * allowing different use cases (LSC, COCO, custom datasets, etc.) to use
 * the same benchmark infrastructure with different settings.
 *
 * @param name Human-readable name for the benchmark (used in reports)
 * @param baseUrl The SPARQL endpoint URL to benchmark
 * @param queriesDir Directory containing .sparql or .rq query files
 * @param reportsDir Directory where benchmark reports will be saved
 * @param warmupRuns Number of warmup iterations (not included in statistics)
 * @param warmRuns Number of timed iterations after warmup
 * @param connectTimeoutMs HTTP connection timeout in milliseconds
 * @param readTimeoutMs HTTP read timeout in milliseconds
 */
data class BenchmarkConfig(
    val name: String,
    val baseUrl: String = "http://localhost:8080/query/sparql",
    val queriesDir: String,
    val reportsDir: String,
    val warmupRuns: Int = 3,
    val warmRuns: Int = 10,
    val connectTimeoutMs: Int = 30000,
    val readTimeoutMs: Int = 60000
) {
    companion object {
        /**
         * Creates a configuration for LSC (Lifelog Search Challenge) benchmarks.
         */
        fun lsc(
            baseUrl: String = "http://localhost:8080/query/sparql",
            warmupRuns: Int = 3,
            warmRuns: Int = 10
        ) = BenchmarkConfig(
            name = "LSC SPARQL Benchmark",
            baseUrl = baseUrl,
            queriesDir = "src/test/resources/lsc_sparql_queries",
            reportsDir = "benchmark_reports/lsc",
            warmupRuns = warmupRuns,
            warmRuns = warmRuns
        )
    }

    /**
     * Validates the configuration.
     * @throws IllegalArgumentException if the configuration is invalid
     */
    fun validate() {
        require(name.isNotBlank()) { "Benchmark name cannot be blank" }
        require(baseUrl.isNotBlank()) { "Base URL cannot be blank" }
        require(queriesDir.isNotBlank()) { "Queries directory cannot be blank" }
        require(reportsDir.isNotBlank()) { "Reports directory cannot be blank" }
        require(warmupRuns >= 0) { "Warmup runs must be non-negative" }
        require(warmRuns >= 0) { "Warm runs must be non-negative" }
        require(warmupRuns > 0 || warmRuns > 0) { "At least one of warmupRuns or warmRuns must be positive" }
        require(connectTimeoutMs > 0) { "Connect timeout must be positive" }
        require(readTimeoutMs > 0) { "Read timeout must be positive" }
    }
}

