package org.megras.benchmark.core

/**
 * Base configuration for all benchmarks.
 */
interface BenchmarkConfig {
    val name: String
    val reportsDir: String

    fun validate()
}

/**
 * Configuration for SPARQL-based benchmarks.
 */
data class SparqlBenchmarkConfig(
    override val name: String,
    val baseUrl: String = "http://localhost:8080",
    val sparqlEndpoint: String = "$baseUrl/query/sparql",
    override val reportsDir: String,
    val warmupRuns: Int = 3,
    val measuredRuns: Int = 10,
    val connectTimeoutMs: Int = 30000,
    val readTimeoutMs: Int = 60000
) : BenchmarkConfig {
    override fun validate() {
        require(name.isNotBlank()) { "Benchmark name cannot be blank" }
        require(sparqlEndpoint.isNotBlank()) { "SPARQL endpoint cannot be blank" }
        require(reportsDir.isNotBlank()) { "Reports directory cannot be blank" }
        require(warmupRuns >= 0) { "Warmup runs must be non-negative" }
        require(measuredRuns >= 0) { "Measured runs must be non-negative" }
        require(warmupRuns > 0 || measuredRuns > 0) { "At least one of warmupRuns or measuredRuns must be positive" }
        require(connectTimeoutMs > 0) { "Connect timeout must be positive" }
        require(readTimeoutMs > 0) { "Read timeout must be positive" }
    }
}

/**
 * Result of benchmarking a single query.
 */
data class QueryBenchmarkResult(
    val queryName: String,
    val queryContent: String,
    val coldStartMs: Long?,
    val resultCount: Int,
    val latencyStats: BenchmarkStatistics.LatencyStats,
    val throughputStats: BenchmarkStatistics.ThroughputStats,
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * A single data point in a parametric benchmark (varying an independent variable).
 */
data class ParametricDataPoint(
    val parameterName: String,
    val parameterValue: Any,
    val queryName: String,
    val queryContent: String,
    val coldStartMs: Long?,
    val resultCount: Int,
    val latencyStats: BenchmarkStatistics.LatencyStats,
    val throughputStats: BenchmarkStatistics.ThroughputStats,
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * Result of a parametric benchmark (multiple data points for different parameter values).
 */
data class ParametricBenchmarkResult(
    val benchmarkName: String,
    val parameterName: String,
    val dataPoints: List<ParametricDataPoint>,
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * Base class for SPARQL-based benchmark runners.
 */
abstract class BaseSparqlBenchmarkRunner(
    protected val config: SparqlBenchmarkConfig
) {
    protected val sparqlClient: SparqlClient = SparqlClient(
        endpoint = config.sparqlEndpoint,
        connectTimeoutMs = config.connectTimeoutMs,
        readTimeoutMs = config.readTimeoutMs
    )

    init {
        config.validate()
    }

    /**
     * Check if the endpoint is available before running.
     */
    fun checkEndpoint(): Boolean {
        if (!sparqlClient.isAvailable()) {
            throw IllegalStateException(
                "SPARQL endpoint is not available at ${config.sparqlEndpoint}\n" +
                "Please start the server before running benchmarks."
            )
        }
        return true
    }

    /**
     * Execute a single query benchmark.
     */
    protected fun benchmarkQuery(
        queryName: String,
        query: String,
        warmupRuns: Int = config.warmupRuns,
        measuredRuns: Int = config.measuredRuns,
        verbose: Boolean = true
    ): QueryBenchmarkResult {
        if (verbose) {
            println("Benchmarking: $queryName")
            println("  Query: ${query.take(100)}${if (query.length > 100) "..." else ""}")
        }

        val (coldStart, results) = sparqlClient.executeQueryWithWarmup(
            query = query,
            warmupRuns = warmupRuns,
            measuredRuns = measuredRuns
        ) { phase, run, result ->
            if (verbose) {
                val phaseLabel = if (phase == "warmup") "Warmup" else "Run"
                val timeLabel = if (phase == "warmup" && run == 1) "(cold start)" else ""
                println("    $phaseLabel $run$timeLabel: ${result.responseTimeMs}ms " +
                        "(${result.resultCount} results) ${if (result.success) "✓" else "✗ ${result.errorMessage}"}")
            }
        }

        val successfulTimes = results.filter { it.success }.map { it.responseTimeMs }
        val latencyStats = BenchmarkStatistics.calculateLatencyStats(successfulTimes, results.size)
        val throughputStats = BenchmarkStatistics.calculateThroughputFromLatency(successfulTimes)
        val resultCount = results.firstOrNull { it.success }?.resultCount ?: 0

        if (verbose) {
            println("  Stats: min=${latencyStats.minMs}ms, max=${latencyStats.maxMs}ms, " +
                    "mean=${BenchmarkStatistics.formatMs(latencyStats.meanMs)}ms, " +
                    "median=${BenchmarkStatistics.formatMs(latencyStats.medianMs)}ms")
        }

        return QueryBenchmarkResult(
            queryName = queryName,
            queryContent = query,
            coldStartMs = coldStart?.responseTimeMs,
            resultCount = resultCount,
            latencyStats = latencyStats,
            throughputStats = throughputStats
        )
    }

    /**
     * Print a standard header for the benchmark.
     */
    protected fun printHeader() {
        println("=".repeat(80))
        println(config.name)
        println("=".repeat(80))
        println()
    }

    /**
     * Print a standard footer for the benchmark.
     */
    protected fun printFooter() {
        println()
        println("=".repeat(80))
        println("Benchmark Complete!")
        println("=".repeat(80))
    }

    /**
     * Print a section divider.
     */
    protected fun printSection(title: String) {
        println()
        println("-".repeat(80))
        println(title)
        println("-".repeat(80))
    }
}

