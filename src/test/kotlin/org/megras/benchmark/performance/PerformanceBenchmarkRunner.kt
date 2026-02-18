package org.megras.benchmark.performance

import org.megras.benchmark.core.*
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Configuration for MeGraS server.
 */
data class MegrasServerConfig(
    val objectStoreBase: String = "store",
    val httpPort: Int = 8080,
    val backend: String = "POSTGRES",
    val postgresHost: String = "localhost",
    val postgresPort: Int = 5432,
    val postgresDatabase: String = "megras",
    val postgresUser: String = "megras",
    val postgresPassword: String = "megras"
) {
    /**
     * Generates a config.json file for MeGraS with the specified settings.
     */
    fun generateConfigJson(): String = """
        {
          "objectStoreBase": "$objectStoreBase",
          "httpPort": $httpPort,
          "backend": "$backend",
          "postgresConnection": {
            "host": "$postgresHost",
            "port": $postgresPort,
            "database": "$postgresDatabase",
            "user": "$postgresUser",
            "password": "$postgresPassword"
          }
        }
    """.trimIndent()

    /**
     * Writes the config to a temporary file and returns the path.
     */
    fun writeToTempFile(): File {
        val tempFile = File.createTempFile("megras-config-", ".json")
        tempFile.deleteOnExit()
        tempFile.writeText(generateConfigJson())
        return tempFile
    }
}

/**
 * Configuration for infrastructure restarts during benchmarks.
 */
data class InfrastructureConfig(
    /** Docker container name for PostgreSQL (e.g., "megrasdb") */
    val postgresContainerName: String = "megrasdb",
    /** Path to MeGraS JAR file for restarting the server (auto-detected if null) */
    val megrasJarPath: String? = null,
    /** Working directory for MeGraS (defaults to current directory) */
    val megrasWorkDir: String = ".",
    /** JVM arguments for MeGraS */
    val megrasJvmArgs: List<String> = listOf("-Xmx4g"),
    /** Time to wait after starting infrastructure (ms) */
    val startupWaitMs: Long = 5000,
    /** Whether to enable automatic restarts (if false, manual pauses are used) */
    val autoRestart: Boolean = true,
    /** Custom MeGraS server configuration (if null, uses default config.json) */
    val megrasServerConfig: MegrasServerConfig? = null
) {
    /**
     * Gets the MeGraS JAR path, auto-detecting from build directory if not specified.
     */
    fun getResolvedJarPath(): String? {
        if (megrasJarPath != null) return megrasJarPath

        // Try to find the shadow JAR in build/libs
        val buildLibs = java.io.File("build/libs")
        if (buildLibs.exists()) {
            val shadowJar = buildLibs.listFiles()?.find {
                it.name.endsWith("-all.jar") || it.name.contains("shadow")
            }
            if (shadowJar != null) return shadowJar.absolutePath

            // Fall back to any JAR
            val anyJar = buildLibs.listFiles()?.find { it.name.endsWith(".jar") }
            if (anyJar != null) return anyJar.absolutePath
        }
        return null
    }
}

/**
 * Configuration for performance benchmarks.
 */
data class PerformanceBenchmarkConfig(
    override val name: String,
    val baseUrl: String = "http://localhost:8080",
    override val reportsDir: String = "benchmark_reports/performance",
    val warmupRuns: Int = 3,
    val measuredRuns: Int = 10,
    val connectTimeoutMs: Int = 30000,
    val readTimeoutMs: Int = 120000,
    val dbConfig: DatabaseOperations.DbConfig = DatabaseOperations.DbConfig(),
    val k: Int = 10,  // Default k for k-NN queries
    val infrastructureConfig: InfrastructureConfig = InfrastructureConfig()
) : BenchmarkConfig {
    val sparqlEndpoint: String get() = "$baseUrl/query/sparql"

    override fun validate() {
        require(name.isNotBlank()) { "Benchmark name cannot be blank" }
        require(baseUrl.isNotBlank()) { "Base URL cannot be blank" }
        require(reportsDir.isNotBlank()) { "Reports directory cannot be blank" }
        require(warmupRuns >= 0) { "Warmup runs must be non-negative" }
        require(measuredRuns > 0) { "Measured runs must be positive" }
        require(k > 0) { "k must be positive" }
    }
}

/**
 * Base runner for performance analysis benchmarks.
 * Provides common functionality for all performance experiments.
 */
abstract class PerformanceBenchmarkRunner(
    protected val config: PerformanceBenchmarkConfig
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
     * Check if the endpoint is available.
     */
    protected fun checkEndpoint(): Boolean {
        if (!sparqlClient.isAvailable()) {
            throw IllegalStateException(
                "SPARQL endpoint is not available at ${config.sparqlEndpoint}\n" +
                "Please start the server before running benchmarks."
            )
        }
        return true
    }

    /**
     * Execute a query benchmark with standard warmup/measured phases.
     */
    protected fun benchmarkQuery(
        queryName: String,
        query: String,
        warmupRuns: Int = config.warmupRuns,
        measuredRuns: Int = config.measuredRuns,
        verbose: Boolean = true,
        metadata: Map<String, Any> = emptyMap()
    ): QueryBenchmarkResult {
        if (verbose) {
            println("  Benchmarking: $queryName")
            println("    Query: ${query.replace("\n", " ").take(80)}...")
        }

        val (coldStart, results) = sparqlClient.executeQueryWithWarmup(
            query = query,
            warmupRuns = warmupRuns,
            measuredRuns = measuredRuns
        ) { phase, run, result ->
            if (verbose) {
                val phaseLabel = if (phase == "warmup") "Warmup" else "Run"
                val coldLabel = if (phase == "warmup" && run == 1) " (cold)" else ""
                print("    $phaseLabel $run$coldLabel: ${result.responseTimeMs}ms")
                println(if (result.success) " (${result.resultCount} results)" else " ERROR: ${result.errorMessage}")
            }
        }

        val successfulTimes = results.filter { it.success }.map { it.responseTimeMs }
        val latencyStats = BenchmarkStatistics.calculateLatencyStats(successfulTimes, results.size)
        val throughputStats = BenchmarkStatistics.calculateThroughputFromLatency(successfulTimes)
        val resultCount = results.firstOrNull { it.success }?.resultCount ?: 0

        if (verbose) {
            println("    → min=${latencyStats.minMs}ms, median=${BenchmarkStatistics.formatMs(latencyStats.medianMs)}ms, " +
                    "mean=${BenchmarkStatistics.formatMs(latencyStats.meanMs)}ms, max=${latencyStats.maxMs}ms")
        }

        return QueryBenchmarkResult(
            queryName = queryName,
            queryContent = query,
            coldStartMs = coldStart?.responseTimeMs,
            resultCount = resultCount,
            latencyStats = latencyStats,
            throughputStats = throughputStats,
            metadata = metadata
        )
    }

    /**
     * Run a parametric experiment varying one parameter.
     */
    protected fun runParametricExperiment(
        experimentName: String,
        parameterName: String,
        parameterValues: List<Pair<String, Any>>,
        queryGenerator: (Any) -> String,
        verbose: Boolean = true
    ): ParametricBenchmarkResult {
        if (verbose) {
            println()
            println("-".repeat(80))
            println("Experiment: $experimentName")
            println("Parameter: $parameterName")
            println("-".repeat(80))
        }

        val dataPoints = mutableListOf<ParametricDataPoint>()

        for ((label, value) in parameterValues) {
            val query = queryGenerator(value)
            val result = benchmarkQuery(
                queryName = "$experimentName ($label)",
                query = query,
                verbose = verbose,
                metadata = mapOf(parameterName to value)
            )

            dataPoints.add(ParametricDataPoint(
                parameterName = parameterName,
                parameterValue = value,
                queryName = result.queryName,
                queryContent = result.queryContent,
                coldStartMs = result.coldStartMs,
                resultCount = result.resultCount,
                latencyStats = result.latencyStats,
                throughputStats = result.throughputStats,
                metadata = result.metadata
            ))
        }

        return ParametricBenchmarkResult(
            benchmarkName = experimentName,
            parameterName = parameterName,
            dataPoints = dataPoints
        )
    }

    /**
     * Print standard header.
     */
    protected fun printHeader() {
        println("=".repeat(80))
        println(config.name)
        println("=".repeat(80))
        println()
        println("Configuration:")
        println("  Endpoint: ${config.sparqlEndpoint}")
        println("  Warmup runs: ${config.warmupRuns}")
        println("  Measured runs: ${config.measuredRuns}")
        println("  k (nearest neighbors): ${config.k}")
        println()
    }

    /**
     * Print standard footer.
     */
    protected fun printFooter() {
        println()
        println("=".repeat(80))
        println("Benchmark Complete!")
        println("=".repeat(80))
    }

    /**
     * Extract a subject URI from the first result of a SPARQL query.
     * Useful for obtaining a specific subject for subsequent vector queries.
     *
     * @param query The SPARQL query to execute (should return a ?s binding)
     * @return The subject URI from the first result, or null if not found
     */
    protected fun extractSubjectUri(query: String): String? {
        val result = sparqlClient.executeQuery(query, includeRawResponse = true)
        if (!result.success || result.rawResponse == null) {
            return null
        }

        return try {
            val json = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(result.rawResponse)
            val bindings = json.path("results").path("bindings")
            if (bindings.isArray && bindings.size() > 0) {
                val firstBinding = bindings[0]
                // Try to get the "s" variable (subject)
                val subjectNode = firstBinding.path("s").path("value")
                if (!subjectNode.isMissingNode) {
                    subjectNode.asText()
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Waits for the SPARQL endpoint to become available again after a restart.
     * Useful for benchmarks that require infrastructure restarts between tests.
     *
     * @param maxWaitSeconds Maximum time to wait for the endpoint
     * @param pollIntervalMs Interval between availability checks
     */
    protected fun waitForEndpoint(maxWaitSeconds: Int = 60, pollIntervalMs: Long = 2000) {
        println("  Waiting for endpoint to be available...")
        val startTime = System.currentTimeMillis()
        val maxWaitMs = maxWaitSeconds * 1000L

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            if (sparqlClient.isAvailable()) {
                println("  Endpoint is available!")
                return
            }
            print(".")
            Thread.sleep(pollIntervalMs)
        }

        throw IllegalStateException("Endpoint did not become available within $maxWaitSeconds seconds")
    }

    /**
     * Waits for the user to press ENTER. Uses multiple approaches to ensure it works
     * in different environments (IDE, Gradle, command line).
     *
     * This is useful for benchmarks that require manual infrastructure restarts
     * between test phases (e.g., restarting PostgreSQL and MeGraS).
     */
    protected fun waitForEnter() {
        System.out.flush()

        // Try using Console first (works in real terminals)
        val console = System.console()
        if (console != null) {
            console.readLine()
            return
        }

        // Fallback: Use Scanner with System.in
        try {
            val scanner = java.util.Scanner(System.`in`)
            if (scanner.hasNextLine()) {
                scanner.nextLine()
            } else {
                // If no input available, wait with a fixed delay as last resort
                println("  (No interactive input available, waiting 30 seconds...)")
                println("  (Run with --console=plain for interactive mode)")
                Thread.sleep(30000)
            }
        } catch (e: Exception) {
            // If all else fails, wait 30 seconds
            println("  (Could not read input, waiting 30 seconds...)")
            Thread.sleep(30000)
        }
    }

    // Track the MeGraS process if we start it
    private var megrasProcess: Process? = null

    /**
     * Restarts the infrastructure (PostgreSQL container and MeGraS) to ensure a clean state.
     * If auto-restart is not configured, falls back to manual pause.
     */
    protected fun restartInfrastructure(message: String = "Restarting infrastructure...") {
        val infraConfig = config.infrastructureConfig
        val resolvedJarPath = infraConfig.getResolvedJarPath()


        if (!infraConfig.autoRestart) {
            // Fall back to manual pause
            println()
            println("=".repeat(80))
            println("PAUSE: You may now restart PostgreSQL and MeGraS if needed.")
            println(message)
            println("Press ENTER to continue...")
            println("=".repeat(80))
            waitForEnter()
            waitForEndpoint()
            return
        }

        println()
        println("-".repeat(80))
        println(message)
        println("-".repeat(80))

        // 1. Stop MeGraS first
        stopMegras()

        // 2. Stop PostgreSQL container
        stopPostgresContainer(infraConfig.postgresContainerName)

        // 3. Start PostgreSQL container
        startPostgresContainer(infraConfig.postgresContainerName)

        // 4. Start MeGraS
        if (resolvedJarPath != null) {
            startMegras(resolvedJarPath, infraConfig.megrasWorkDir, infraConfig.megrasJvmArgs, infraConfig.megrasServerConfig)
        } else {
            println("  Warning: No MeGraS JAR found. Please start MeGraS manually.")
        }

        // Wait for startup
        println("  Waiting ${infraConfig.startupWaitMs}ms for startup...")
        Thread.sleep(infraConfig.startupWaitMs)

        // Wait for endpoint to be available
        waitForEndpoint()

        println("  Infrastructure restart complete.")
        println()
    }

    /**
     * Stops the PostgreSQL Docker container.
     */
    private fun stopPostgresContainer(containerName: String) {
        println("  Stopping PostgreSQL container: $containerName")
        val stopResult = runCommand("docker", "stop", containerName)
        if (!stopResult.first) {
            println("    Warning: Failed to stop container: ${stopResult.second}")
        } else {
            println("    PostgreSQL container stopped.")
        }
    }

    /**
     * Starts the PostgreSQL Docker container.
     */
    private fun startPostgresContainer(containerName: String) {
        println("  Starting PostgreSQL container: $containerName")
        val startResult = runCommand("docker", "start", containerName)
        if (!startResult.first) {
            throw IllegalStateException("Failed to start PostgreSQL container: ${startResult.second}")
        }
        println("    PostgreSQL container started.")
    }

    /**
     * Stops the MeGraS process if it was started by us, or tries to find and kill it.
     */
    private fun stopMegras() {
        println("  Stopping MeGraS...")

        // If we have a reference to the process we started, destroy it
        megrasProcess?.let { process ->
            if (process.isAlive) {
                process.destroy()
                process.waitFor(10, TimeUnit.SECONDS)
                if (process.isAlive) {
                    process.destroyForcibly()
                }
            }
            megrasProcess = null
            println("    MeGraS process stopped.")
            return
        }

        // Otherwise, try to find and kill MeGraS by port
        // This is a best-effort approach for when MeGraS was started externally
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        if (isWindows) {
            // Find process using port 8080 and kill it
            val findResult = runCommand("cmd", "/c", "netstat -ano | findstr :8080")
            if (findResult.first && findResult.second.isNotBlank()) {
                // Extract PID from netstat output
                val lines = findResult.second.lines().filter { it.contains("LISTENING") }
                for (line in lines) {
                    val parts = line.trim().split("\\s+".toRegex())
                    if (parts.isNotEmpty()) {
                        val pid = parts.last()
                        runCommand("taskkill", "/F", "/PID", pid)
                    }
                }
            }
        } else {
            // Unix-like: use fuser or lsof
            runCommand("sh", "-c", "fuser -k 8080/tcp 2>/dev/null || lsof -ti:8080 | xargs kill -9 2>/dev/null || true")
        }

        // Give it a moment to shut down
        Thread.sleep(2000)
        println("    MeGraS stopped (or was not running).")
    }

    // Track the custom config file if we create one
    private var customConfigFile: File? = null

    /**
     * Starts MeGraS from the JAR file, optionally with a custom config.
     */
    private fun startMegras(jarPath: String, workDir: String, jvmArgs: List<String>, serverConfig: MegrasServerConfig? = null) {
        println("  Starting MeGraS from: $jarPath")

        val jarFile = File(jarPath)
        if (!jarFile.exists()) {
            throw IllegalStateException("MeGraS JAR not found: $jarPath")
        }

        // Generate custom config file if server config is provided
        val configPath = if (serverConfig != null) {
            customConfigFile = serverConfig.writeToTempFile()
            println("    Using custom config: ${customConfigFile!!.absolutePath}")
            println("    Database: ${serverConfig.postgresHost}:${serverConfig.postgresPort}/${serverConfig.postgresDatabase}")
            println("    Object Store: ${serverConfig.objectStoreBase}")
            customConfigFile!!.absolutePath
        } else {
            println("    Using default config.json")
            null
        }

        val command = mutableListOf("java")
        command.addAll(jvmArgs)
        command.add("-jar")
        command.add(jarFile.absolutePath)
        // MeGraS takes config file as first positional argument (not --config)
        if (configPath != null) {
            command.add(configPath)
        }

        val processBuilder = ProcessBuilder(command)
            .directory(File(workDir))
            .redirectErrorStream(true)

        // Start the process and keep a reference
        megrasProcess = processBuilder.start()

        // Start a thread to consume output (prevents blocking)
        Thread {
            megrasProcess?.inputStream?.bufferedReader()?.use { reader ->
                reader.lines().forEach { line ->
                    // Optionally log MeGraS output
                    // println("    [MeGraS] $line")
                }
            }
        }.start()

        println("    MeGraS starting...")
    }

    /**
     * Runs a command and returns (success, output).
     */
    private fun runCommand(vararg command: String): Pair<Boolean, String> {
        return try {
            val process = ProcessBuilder(*command)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            Pair(exitCode == 0, output.trim())
        } catch (e: Exception) {
            Pair(false, e.message ?: "Unknown error")
        }
    }

    /**
     * Stops the infrastructure (MeGraS and PostgreSQL container) at the end of a benchmark.
     * Call this at the end of benchmark execution to clean up resources.
     */
    protected fun stopInfrastructure() {
        val infraConfig = config.infrastructureConfig

        if (!infraConfig.autoRestart) {
            println()
            println("Benchmark complete. Infrastructure left running (autoRestart disabled).")
            return
        }

        println()
        println("-".repeat(80))
        println("Stopping infrastructure...")
        println("-".repeat(80))

        // Stop MeGraS
        stopMegras()

        // Stop PostgreSQL container
        stopPostgresContainer(infraConfig.postgresContainerName)

        println("  Infrastructure stopped.")
        println()
    }

    /**
     * Save reports to disk.
     */
    protected fun saveReports(
        results: List<ParametricBenchmarkResult>,
        timestamp: String = BenchmarkReportGenerator.generateTimestamp()
    ) {
        val reportsDir = BenchmarkReportGenerator.ensureReportsDir(config.reportsDir)

        // Markdown report
        val mdContent = generateMarkdownReport(results)
        BenchmarkReportGenerator.saveReport(reportsDir, "performance_${timestamp}.md", mdContent)

        // CSV report
        val csvContent = generateCsvReport(results)
        BenchmarkReportGenerator.saveReport(reportsDir, "performance_${timestamp}.csv", csvContent)

        // JSON report
        val jsonData = generateJsonReport(results, timestamp)
        BenchmarkReportGenerator.saveJsonReport(reportsDir, "performance_${timestamp}.json", jsonData)
    }

    /**
     * Generate Markdown report content.
     */
    protected open fun generateMarkdownReport(results: List<ParametricBenchmarkResult>): String {
        return buildString {
            appendLine("# ${config.name} Report")
            appendLine()
            appendLine("Generated: ${BenchmarkReportGenerator.generateReadableTimestamp()}")
            appendLine()
            appendLine("## Configuration")
            appendLine()
            appendLine("| Parameter | Value |")
            appendLine("|-----------|-------|")
            appendLine("| Endpoint | ${config.sparqlEndpoint} |")
            appendLine("| Warmup Runs | ${config.warmupRuns} |")
            appendLine("| Measured Runs | ${config.measuredRuns} |")
            appendLine("| k (NN) | ${config.k} |")
            appendLine()

            for (result in results) {
                appendLine("## ${result.benchmarkName}")
                appendLine()
                appendLine("**Parameter:** ${result.parameterName}")
                appendLine()
                appendLine("| ${result.parameterName} | Results | Cold Start (ms) | Min (ms) | Max (ms) | Mean (ms) | Median (ms) | Std Dev (ms) | Throughput (ops/s) |")
                appendLine("|---|---------|-----------------|----------|----------|-----------|-------------|--------------|-------------------|")

                for (point in result.dataPoints) {
                    appendLine("| ${point.parameterValue} | ${point.resultCount} | ${point.coldStartMs ?: "-"} | " +
                            "${point.latencyStats.minMs} | ${point.latencyStats.maxMs} | " +
                            "${BenchmarkStatistics.formatMs(point.latencyStats.meanMs)} | " +
                            "${BenchmarkStatistics.formatMs(point.latencyStats.medianMs)} | " +
                            "${BenchmarkStatistics.formatMs(point.latencyStats.stdDevMs)} | " +
                            "${BenchmarkStatistics.formatOpsPerSec(point.throughputStats.meanOpsPerSec)} |")
                }
                appendLine()
            }
        }
    }

    /**
     * Generate CSV report content.
     */
    protected open fun generateCsvReport(results: List<ParametricBenchmarkResult>): String {
        return buildString {
            appendLine("Experiment,Parameter,Value,Results,ColdStart_ms,Min_ms,Max_ms,Mean_ms,Median_ms,StdDev_ms,P95_ms,P99_ms,Throughput_ops_s")
            for (result in results) {
                for (point in result.dataPoints) {
                    appendLine("${result.benchmarkName},${result.parameterName},${point.parameterValue}," +
                            "${point.resultCount},${point.coldStartMs ?: ""}," +
                            "${point.latencyStats.minMs},${point.latencyStats.maxMs}," +
                            "${BenchmarkStatistics.formatMs(point.latencyStats.meanMs)}," +
                            "${BenchmarkStatistics.formatMs(point.latencyStats.medianMs)}," +
                            "${BenchmarkStatistics.formatMs(point.latencyStats.stdDevMs)}," +
                            "${BenchmarkStatistics.formatMs(point.latencyStats.p95Ms)}," +
                            "${BenchmarkStatistics.formatMs(point.latencyStats.p99Ms)}," +
                            "${BenchmarkStatistics.formatOpsPerSec(point.throughputStats.meanOpsPerSec)}")
                }
            }
        }
    }

    /**
     * Generate JSON report data.
     */
    protected open fun generateJsonReport(
        results: List<ParametricBenchmarkResult>,
        timestamp: String
    ): Map<String, Any> {
        return mapOf(
            "benchmark" to config.name,
            "timestamp" to timestamp,
            "config" to mapOf(
                "endpoint" to config.sparqlEndpoint,
                "warmupRuns" to config.warmupRuns,
                "measuredRuns" to config.measuredRuns,
                "k" to config.k
            ),
            "experiments" to results.map { result ->
                mapOf(
                    "name" to result.benchmarkName,
                    "parameter" to result.parameterName,
                    "dataPoints" to result.dataPoints.map { point ->
                        mapOf(
                            "parameterValue" to point.parameterValue,
                            "resultCount" to point.resultCount,
                            "coldStartMs" to point.coldStartMs,
                            "latency" to mapOf(
                                "minMs" to point.latencyStats.minMs,
                                "maxMs" to point.latencyStats.maxMs,
                                "meanMs" to point.latencyStats.meanMs,
                                "medianMs" to point.latencyStats.medianMs,
                                "stdDevMs" to point.latencyStats.stdDevMs,
                                "p95Ms" to point.latencyStats.p95Ms,
                                "p99Ms" to point.latencyStats.p99Ms,
                                "allTimesMs" to point.latencyStats.allTimesMs
                            ),
                            "throughput" to mapOf(
                                "meanOpsPerSec" to point.throughputStats.meanOpsPerSec,
                                "medianOpsPerSec" to point.throughputStats.medianOpsPerSec
                            )
                        )
                    }
                )
            }
        )
    }
}


