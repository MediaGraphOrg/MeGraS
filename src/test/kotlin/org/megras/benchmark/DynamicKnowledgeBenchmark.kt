package org.megras.benchmark

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.megras.benchmark.core.BenchmarkStatistics
import org.megras.benchmark.core.DatabaseOperations
import org.megras.benchmark.performance.InfrastructureConfig
import org.megras.benchmark.performance.MegrasServerConfig
import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.graphstore.db.PostgresStore
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Dynamic Knowledge Generation Benchmark
 *
 * Evaluates the performance trade-offs of the MeGraS virtualized resolution layer by comparing
 * materialized vector storage against on-the-fly feature extraction.
 *
 * For each iteration:
 * 1. Drop and recreate database
 * 2. Ingest TSV with pre-computed embeddings → Materialized state
 * 3. Measure query latency (warmup + measured queries, using median)
 * 4. Drop and recreate database
 * 5. Ingest TSV without embeddings → Dynamic state
 * 6. Measure SINGLE query latency (triggers on-the-fly CLIP computation)
 *
 * Note: The dynamic state only measures ONE query because derived embeddings are
 * persisted after the first query. Subsequent queries would read from storage,
 * defeating the purpose of measuring dynamic inference time.
 */
class DynamicKnowledgeBenchmark {

    companion object {
        private const val DEFAULT_BASE_URL = "http://localhost:8080"
        private const val DEFAULT_REPORTS_DIR = "benchmark_reports/dynamic_knowledge"
        private const val DEFAULT_ITERATIONS = 10
        private const val DEFAULT_WARMUP_QUERIES = 3  // Warmup queries per state (discarded)
        private const val DEFAULT_MEASURED_QUERIES = 10  // Measured queries per state (warm runs)
        private const val DEFAULT_IMAGE_LIMIT = 1000
        private const val DEFAULT_READ_TIMEOUT_MS = 600000

        private const val DEFAULT_TSV_BASE = "MeGraS-SYNTH-base-no-embeddings.tsv"
        private const val DEFAULT_TSV_EMBEDDINGS = "MeGraS-SYNTH-embeddings.tsv"

        private const val DEFAULT_DB_HOST = "localhost"
        private const val DEFAULT_DB_PORT = 5432
        private const val DEFAULT_DB_NAME = "megras_clip"
        private const val DEFAULT_DB_USER = "megras"
        private const val DEFAULT_DB_PASSWORD = "megras"

        private const val DEFAULT_POSTGRES_CONTAINER = "megrasdb"
        private const val DEFAULT_OBJECT_STORE = "store-megras-synth"

        private const val EMBEDDING_DIMENSIONS = 512
        private const val BYTES_PER_FLOAT = 4

        private val TSV_SPLITTER = "\t(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)".toRegex()
    }

    data class Config(
        val name: String = "Dynamic Knowledge Generation Benchmark",
        val baseUrl: String = DEFAULT_BASE_URL,
        val reportsDir: String = DEFAULT_REPORTS_DIR,
        val iterations: Int = DEFAULT_ITERATIONS,
        val warmupQueries: Int = DEFAULT_WARMUP_QUERIES,
        val measuredQueries: Int = DEFAULT_MEASURED_QUERIES,
        val imageLimit: Int = DEFAULT_IMAGE_LIMIT,
        val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
        val tsvBase: String = DEFAULT_TSV_BASE,
        val tsvEmbeddings: String = DEFAULT_TSV_EMBEDDINGS,
        val dbHost: String = DEFAULT_DB_HOST,
        val dbPort: Int = DEFAULT_DB_PORT,
        val dbName: String = DEFAULT_DB_NAME,
        val dbUser: String = DEFAULT_DB_USER,
        val dbPassword: String = DEFAULT_DB_PASSWORD,
        val postgresContainerName: String = DEFAULT_POSTGRES_CONTAINER,
        val objectStoreBase: String = DEFAULT_OBJECT_STORE,
        val autoRestart: Boolean = true
    ) {
        val sparqlEndpoint: String get() = "$baseUrl/query/sparql"
        val dbConnectionString: String get() = "$dbHost:$dbPort/$dbName"
        val adminConnectionString: String get() = "$dbHost:$dbPort/postgres"

        /** Creates infrastructure config with MeGraS server config pointing to this benchmark's database */
        fun toInfrastructureConfig(): InfrastructureConfig = InfrastructureConfig(
            postgresContainerName = postgresContainerName,
            autoRestart = autoRestart,
            megrasServerConfig = MegrasServerConfig(
                objectStoreBase = objectStoreBase,
                postgresHost = dbHost,
                postgresPort = dbPort,
                postgresDatabase = dbName,
                postgresUser = dbUser,
                postgresPassword = dbPassword
            )
        )
    }

    data class IterationResult(
        val iteration: Int,
        val state: String,
        val ingestionTimeMs: Long,
        val quadsIngested: Long,
        val coldStartMs: Long,      // First query (warmup or measured if no warmup)
        val queryLatencyMs: Long,   // Median of measured queries (or single query for dynamic)
        val embeddingCount: Int,
        val success: Boolean
    )

    data class LatencyStats(
        val minMs: Long,
        val maxMs: Long,
        val meanMs: Double,
        val medianMs: Double,
        val stdDevMs: Double,
        val allTimesMs: List<Long>
    ) {
        companion object {
            fun fromBenchmarkStats(stats: BenchmarkStatistics.LatencyStats) = LatencyStats(
                minMs = stats.minMs,
                maxMs = stats.maxMs,
                meanMs = stats.meanMs,
                medianMs = stats.medianMs,
                stdDevMs = stats.stdDevMs,
                allTimesMs = stats.allTimesMs
            )
        }
    }

    data class BenchmarkResult(
        val materializedColdStartMs: Long,  // Single cold start value
        val materializedStats: LatencyStats,  // Stats from warm queries
        val dynamicStats: LatencyStats,  // For dynamic, cold start IS the measurement
        val inferenceTaxMs: Double,
        val inferenceTaxPercent: Double,
        val storageSavingsBytes: Long,
        val storageSavingsKB: Double,
        val allIterations: List<IterationResult>
    )

    private val config = Config()
    private val objectMapper = jacksonObjectMapper()
    private val infraConfig = config.toInfrastructureConfig()

    // Infrastructure management
    private var megrasProcess: Process? = null

    private fun createSparqlRunner() = SparqlBenchmarkRunner(
        BenchmarkConfig(
            name = "CLIP Query",
            baseUrl = config.sparqlEndpoint,
            queriesDir = ".",
            reportsDir = config.reportsDir,
            warmupRuns = config.warmupQueries,
            warmRuns = config.measuredQueries,
            connectTimeoutMs = 30000,
            readTimeoutMs = config.readTimeoutMs
        )
    )

    private val sparqlRunner by lazy { createSparqlRunner() }

    @Test
    fun run() {
        println("=".repeat(80))
        println(config.name)
        println("=".repeat(80))
        println()
        println("Configuration:")
        println("  Base URL: ${config.baseUrl}")
        println("  Iterations: ${config.iterations}")
        println("  Materialized: ${config.warmupQueries} warmup + ${config.measuredQueries} measured queries (median)")
        println("  Dynamic: 1 query (embeddings persist after first query)")
        println("  Image limit: ${config.imageLimit}")
        println("  TSV base (no embeddings): ${config.tsvBase}")
        println("  TSV embeddings only: ${config.tsvEmbeddings}")
        println("  Database: ${config.dbConnectionString}")
        println("  Docker container: ${config.postgresContainerName}")
        println("  Auto-restart: ${config.autoRestart}")
        println()

        if (!File(config.tsvBase).exists()) {
            println("ERROR: ${config.tsvBase} not found")
            return
        }
        if (!File(config.tsvEmbeddings).exists()) {
            println("ERROR: ${config.tsvEmbeddings} not found")
            return
        }

        try {
            val result = runBenchmark()
            saveReports(result)
            printSummary(result)
        } catch (e: Exception) {
            println("ERROR: ${e.message}")
            e.printStackTrace()
        } finally {
            // Stop infrastructure at the end
            stopInfrastructure()
        }
    }

    private fun runBenchmark(): BenchmarkResult {
        val allIterations = mutableListOf<IterationResult>()
        var lastEmbeddingCount = 0
        val clipQuery = """
            PREFIX derived: <http://megras.org/derived/>
            SELECT ?subject ?embedding WHERE { ?subject derived:clipEmbedding ?embedding . } LIMIT ${config.imageLimit}
        """.trimIndent()

        // ===== MATERIALIZED STATE =====
        println("\n  [MATERIALIZED] Restarting infrastructure and resetting database...")
        restartInfrastructure("Preparing for MATERIALIZED state benchmark...")
        resetDatabase()
        // Restart again after database reset to ensure MeGraS connects to fresh DB
        restartInfrastructure("Starting MeGraS with fresh database...")

        println("  [MATERIALIZED] Ingesting ${config.tsvBase}...")
        val matStart = System.currentTimeMillis()
        val matBaseQuads = ingestTsv(config.tsvBase)
        println("    Ingested $matBaseQuads base quads")

        println("  [MATERIALIZED] Ingesting ${config.tsvEmbeddings}...")
        val matEmbeddingQuads = ingestTsv(config.tsvEmbeddings)
        val matIngestionTime = System.currentTimeMillis() - matStart
        val matQuads = matBaseQuads + matEmbeddingQuads
        println("    Ingested $matEmbeddingQuads embedding quads")
        println("    Total: $matQuads quads in ${matIngestionTime}ms")

        println("  [MATERIALIZED] Querying embeddings (${config.warmupQueries} warmup + ${config.measuredQueries} measured)...")
        val matResult = sparqlRunner.benchmarkSingleQuery("clip", clipQuery)
        val matColdStart = matResult.coldStartMs ?: 0
        val matCount = matResult.warmStats?.resultCount ?: 0
        lastEmbeddingCount = matCount

        // Extract individual query timings from warm stats for proper statistics
        val matWarmTimings = matResult.warmStats?.allTimesMs ?: emptyList()
        val matStats = calculateStats(matWarmTimings)

        println("    Cold start: ${matColdStart}ms")
        println("    Warm queries (${matWarmTimings.size} runs): min=${matStats.minMs}ms, max=${matStats.maxMs}ms, median=${"%.2f".format(matStats.medianMs)}ms ($matCount embeddings)")

        allIterations.add(IterationResult(0, "materialized", matIngestionTime, matQuads, matColdStart, matStats.medianMs.toLong(), matCount,
            (matResult.warmStats?.successfulRuns ?: 0) > 0))

        for (i in 1..config.iterations) {
            println()
            println("-".repeat(80))
            println("Iteration $i/${config.iterations}")
            println("-".repeat(80))

            // ===== DYNAMIC STATE =====
            // Note: Only ONE query is measured because the derived embeddings are persisted
            // after the first query. Subsequent queries would read from storage, not trigger inference.
            println("\n  [DYNAMIC] Restarting infrastructure and resetting database...")
            restartInfrastructure("Preparing for DYNAMIC state benchmark (iteration $i)...")
            resetDatabase()
            // Restart again after database reset to ensure MeGraS connects to fresh DB
            restartInfrastructure("Starting MeGraS with fresh database...")

            println("  [DYNAMIC] Ingesting ${config.tsvBase} (no embeddings)...")
            val dynStart = System.currentTimeMillis()
            val dynQuads = ingestTsv(config.tsvBase)
            val dynIngestionTime = System.currentTimeMillis() - dynStart
            println("    Ingested $dynQuads quads in ${dynIngestionTime}ms")

            println("  [DYNAMIC] Querying embeddings (single query - triggers CLIP inference)...")
            // Use a fresh runner with no warmup and only 1 measured run for dynamic state
            val dynamicRunner = SparqlBenchmarkRunner(
                BenchmarkConfig(
                    name = "CLIP Query Dynamic",
                    baseUrl = config.sparqlEndpoint,
                    queriesDir = ".",
                    reportsDir = config.reportsDir,
                    warmupRuns = 0,  // No warmup - we want to measure the actual inference
                    warmRuns = 1,    // Single query - results get persisted after this
                    connectTimeoutMs = 30000,
                    readTimeoutMs = config.readTimeoutMs
                )
            )
            val dynResult = dynamicRunner.benchmarkSingleQuery("clip_dynamic", clipQuery)
            val dynLatency = dynResult.coldStartMs ?: dynResult.warmStats?.allTimesMs?.firstOrNull() ?: 0
            val dynCount = dynResult.warmStats?.resultCount ?: 0
            println("    Query latency (= cold start): ${dynLatency}ms ($dynCount embeddings)")

            // For dynamic, cold start IS the measurement (single query)
            allIterations.add(IterationResult(i, "dynamic", dynIngestionTime, dynQuads, dynLatency, dynLatency, dynCount,
                (dynResult.warmStats?.successfulRuns ?: 0) > 0))
        }

        val dynResults = allIterations.filter { it.state == "dynamic" && it.success }
        val dynStats = calculateStats(dynResults.map { it.queryLatencyMs })

        val inferenceTaxMs = dynStats.meanMs - matStats.meanMs
        val inferenceTaxPercent = if (matStats.meanMs > 0) (inferenceTaxMs / matStats.meanMs) * 100 else 0.0

        return BenchmarkResult(
            materializedColdStartMs = matColdStart,
            materializedStats = matStats,
            dynamicStats = dynStats,
            inferenceTaxMs = inferenceTaxMs,
            inferenceTaxPercent = inferenceTaxPercent,
            storageSavingsBytes = lastEmbeddingCount.toLong() * EMBEDDING_DIMENSIONS * BYTES_PER_FLOAT,
            storageSavingsKB = lastEmbeddingCount.toLong() * EMBEDDING_DIMENSIONS * BYTES_PER_FLOAT / 1024.0,
            allIterations = allIterations
        )
    }

    // ==================== Infrastructure Management ====================

    /**
     * Restarts the infrastructure (PostgreSQL container and MeGraS) to ensure a clean state.
     */
    private fun restartInfrastructure(message: String = "Restarting infrastructure...") {
        if (!config.autoRestart) {
            println()
            println("=".repeat(80))
            println("PAUSE: You may now restart PostgreSQL and MeGraS if needed.")
            println(message)
            println("Press ENTER to continue...")
            println("=".repeat(80))
            readLine()
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
        stopPostgresContainer()

        // 3. Start PostgreSQL container
        startPostgresContainer()

        // 4. Start MeGraS with custom config
        startMegras()

        // Wait for startup
        println("  Waiting ${infraConfig.startupWaitMs}ms for startup...")
        Thread.sleep(infraConfig.startupWaitMs)

        // Wait for endpoint to be available
        waitForEndpoint()

        println("  Infrastructure restart complete.")
        println()
    }

    /**
     * Stops the infrastructure at the end of the benchmark.
     */
    private fun stopInfrastructure() {
        if (!config.autoRestart) {
            println()
            println("Benchmark complete. Infrastructure left running (autoRestart disabled).")
            return
        }

        println()
        println("-".repeat(80))
        println("Stopping infrastructure...")
        println("-".repeat(80))

        stopMegras()
        stopPostgresContainer()

        println("  Infrastructure stopped.")
        println()
    }

    private fun stopPostgresContainer() {
        println("  Stopping PostgreSQL container: ${config.postgresContainerName}")
        val result = runCommand("docker", "stop", config.postgresContainerName)
        if (!result.first) {
            println("    Warning: Failed to stop container: ${result.second}")
        } else {
            println("    PostgreSQL container stopped.")
        }
    }

    private fun startPostgresContainer() {
        println("  Starting PostgreSQL container: ${config.postgresContainerName}")
        val result = runCommand("docker", "start", config.postgresContainerName)
        if (!result.first) {
            throw IllegalStateException("Failed to start PostgreSQL container: ${result.second}")
        }
        println("    PostgreSQL container started.")
        // Give PostgreSQL time to initialize
        Thread.sleep(3000)
    }

    private fun stopMegras() {
        println("  Stopping MeGraS...")

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

        // Try to find and kill MeGraS by port
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        if (isWindows) {
            val findResult = runCommand("cmd", "/c", "netstat -ano | findstr :8080")
            if (findResult.first && findResult.second.isNotBlank()) {
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
            runCommand("sh", "-c", "fuser -k 8080/tcp 2>/dev/null || lsof -ti:8080 | xargs kill -9 2>/dev/null || true")
        }

        Thread.sleep(2000)
        println("    MeGraS stopped (or was not running).")
    }

    private fun startMegras() {
        val jarPath = infraConfig.getResolvedJarPath()
        if (jarPath == null) {
            println("  Warning: No MeGraS JAR found. Please start MeGraS manually.")
            return
        }

        println("  Starting MeGraS from: $jarPath")

        val jarFile = File(jarPath)
        if (!jarFile.exists()) {
            throw IllegalStateException("MeGraS JAR not found: $jarPath")
        }

        // Generate custom config file
        val serverConfig = infraConfig.megrasServerConfig!!
        val configFile = serverConfig.writeToTempFile()
        println("    Using custom config: ${configFile.absolutePath}")
        println("    Database: ${serverConfig.postgresHost}:${serverConfig.postgresPort}/${serverConfig.postgresDatabase}")

        val command = mutableListOf("java")
        command.addAll(infraConfig.megrasJvmArgs)
        command.add("-jar")
        command.add(jarFile.absolutePath)
        // MeGraS takes config file as first positional argument (not --config)
        command.add(configFile.absolutePath)

        val processBuilder = ProcessBuilder(command)
            .directory(File(infraConfig.megrasWorkDir))
            .redirectErrorStream(true)

        megrasProcess = processBuilder.start()

        Thread {
            megrasProcess?.inputStream?.bufferedReader()?.use { reader ->
                reader.lines().forEach { _ -> }
            }
        }.start()

        println("    MeGraS starting...")
    }

    private fun waitForEndpoint(maxWaitSeconds: Int = 60, pollIntervalMs: Long = 2000) {
        println("  Waiting for endpoint to be available...")
        val startTime = System.currentTimeMillis()
        val maxWaitMs = maxWaitSeconds * 1000L

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            if (sparqlRunner.isEndpointAvailable()) {
                println("  Endpoint is available!")
                return
            }
            print(".")
            Thread.sleep(pollIntervalMs)
        }

        throw IllegalStateException("Endpoint did not become available within $maxWaitSeconds seconds")
    }

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

    // ==================== Database Operations ====================

    /**
     * Drops and recreates the database for a clean slate.
     * Uses the shared DatabaseOperations utility.
     */
    private fun resetDatabase() {
        val dbConfig = DatabaseOperations.DbConfig(
            host = config.dbHost,
            port = config.dbPort,
            dbName = config.dbName,
            user = config.dbUser,
            password = config.dbPassword
        )
        DatabaseOperations.resetDatabase(dbConfig)
    }

    /**
     * Creates a fresh PostgresStore and ingests the TSV file.
     */
    private fun ingestTsv(tsvPath: String): Long {
        val store = PostgresStore(
            host = config.dbConnectionString,
            user = config.dbUser,
            password = config.dbPassword
        ).also { it.setup() }

        val batch = mutableSetOf<Quad>()
        var totalQuads = 0L
        var skip = 1 // Skip header

        File(tsvPath).forEachLine { raw ->
            if (skip-- > 0) return@forEachLine

            val line = raw.split(TSV_SPLITTER)
            if (line.size >= 3) {
                val values = line.map { value ->
                    if (value.startsWith('<') && value.endsWith('>')) {
                        QuadValue.of(value.replace(" ", "_"))
                    } else {
                        QuadValue.of(value)
                    }
                }
                batch.add(Quad(values[0], values[1], values[2]))
                totalQuads++

                if (batch.size >= 10000) {
                    store.addAll(batch)
                    batch.clear()
                    print(".")
                }
            }
        }

        if (batch.isNotEmpty()) {
            store.addAll(batch)
        }
        println()
        return totalQuads
    }

    // ==================== Statistics ====================

    private fun calculateStats(times: List<Long>): LatencyStats {
        if (times.isEmpty()) return LatencyStats(0, 0, 0.0, 0.0, 0.0, emptyList())
        val stats = BenchmarkStatistics.calculateLatencyStats(times)
        return LatencyStats(
            minMs = stats.minMs,
            maxMs = stats.maxMs,
            meanMs = stats.meanMs,
            medianMs = stats.medianMs,
            stdDevMs = stats.stdDevMs,
            allTimesMs = stats.allTimesMs
        )
    }

    // ==================== Output ====================

    private fun printSummary(result: BenchmarkResult) {
        println()
        println("=".repeat(80))
        println("BENCHMARK SUMMARY")
        println("=".repeat(80))
        println()
        println("MATERIALIZED STATE (pre-computed embeddings)")
        println("-".repeat(40))
        println("  Cold Start (first query): ${result.materializedColdStartMs} ms")
        println()
        println("  Warm Queries (${config.measuredQueries} runs):")
        println("    Mean:    ${"%.2f".format(result.materializedStats.meanMs)} ms")
        println("    Median:  ${"%.2f".format(result.materializedStats.medianMs)} ms")
        println("    Min:     ${result.materializedStats.minMs} ms")
        println("    Max:     ${result.materializedStats.maxMs} ms")
        println("    Std Dev: ${"%.2f".format(result.materializedStats.stdDevMs)} ms")
        println()
        println("DYNAMIC STATE (on-the-fly CLIP inference)")
        println("-".repeat(40))
        println("  Single Query per Iteration (${config.iterations} iterations):")
        println("    Mean:    ${"%.2f".format(result.dynamicStats.meanMs)} ms")
        println("    Median:  ${"%.2f".format(result.dynamicStats.medianMs)} ms")
        println("    Min:     ${result.dynamicStats.minMs} ms")
        println("    Max:     ${result.dynamicStats.maxMs} ms")
        println("    Std Dev: ${"%.2f".format(result.dynamicStats.stdDevMs)} ms")
        println()
        println("INFERENCE TAX")
        println("-".repeat(40))
        println("  Additional Latency: ${"%.2f".format(result.inferenceTaxMs)} ms")
        println("  Overhead:           ${"%.1f".format(result.inferenceTaxPercent)}%")
        println()
        println("STORAGE SAVINGS")
        println("-".repeat(40))
        println("  Bytes: ${result.storageSavingsBytes}")
        println("  KB:    ${"%.2f".format(result.storageSavingsKB)}")
        println()
        println("=".repeat(80))
    }

    private fun saveReports(result: BenchmarkResult) {
        val reportsDir = File(config.reportsDir).apply { if (!exists()) mkdirs() }
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))

        // Markdown
        File(reportsDir, "dynamic_knowledge_$timestamp.md").writeText(buildString {
            appendLine("# Dynamic Knowledge Generation Benchmark")
            appendLine("\nGenerated: ${LocalDateTime.now()}")
            appendLine("\n## Configuration")
            appendLine("| Parameter | Value |")
            appendLine("|-----------|-------|")
            appendLine("| Iterations (Dynamic) | ${config.iterations} |")
            appendLine("| Warmup Queries (Materialized) | ${config.warmupQueries} |")
            appendLine("| Measured Queries (Materialized) | ${config.measuredQueries} |")
            appendLine("| Image Limit | ${config.imageLimit} |")
            appendLine("| Database | ${config.dbConnectionString} |")
            appendLine("| Docker Container | ${config.postgresContainerName} |")
            appendLine("\n## Results")
            appendLine("\n### Materialized State (Pre-computed Embeddings)")
            appendLine("\n#### Cold Start (First Query)")
            appendLine("| Value | ${result.materializedColdStartMs} ms |")
            appendLine("\n#### Warm Queries (${config.measuredQueries} Runs)")
            appendLine("| Metric | Value (ms) |")
            appendLine("|--------|------------|")
            appendLine("| Mean | ${"%.2f".format(result.materializedStats.meanMs)} |")
            appendLine("| Median | ${"%.2f".format(result.materializedStats.medianMs)} |")
            appendLine("| Min | ${result.materializedStats.minMs} |")
            appendLine("| Max | ${result.materializedStats.maxMs} |")
            appendLine("| Std Dev | ${"%.2f".format(result.materializedStats.stdDevMs)} |")
            appendLine("\n### Dynamic State (On-the-fly CLIP Inference)")
            appendLine("\n#### Single Query per Iteration (${config.iterations} Iterations)")
            appendLine("| Metric | Value (ms) |")
            appendLine("|--------|------------|")
            appendLine("| Mean | ${"%.2f".format(result.dynamicStats.meanMs)} |")
            appendLine("| Median | ${"%.2f".format(result.dynamicStats.medianMs)} |")
            appendLine("| Min | ${result.dynamicStats.minMs} |")
            appendLine("| Max | ${result.dynamicStats.maxMs} |")
            appendLine("| Std Dev | ${"%.2f".format(result.dynamicStats.stdDevMs)} |")
            appendLine("\n### Inference Tax")
            appendLine("| Metric | Value |")
            appendLine("|--------|-------|")
            appendLine("| Additional Latency | ${"%.2f".format(result.inferenceTaxMs)} ms |")
            appendLine("| Overhead | ${"%.1f".format(result.inferenceTaxPercent)}% |")
            appendLine("\n### Storage Savings")
            appendLine("| Metric | Value |")
            appendLine("|--------|-------|")
            appendLine("| Bytes | ${result.storageSavingsBytes} |")
            appendLine("| KB | ${"%.2f".format(result.storageSavingsKB)} |")
            appendLine("\n## Detailed Iterations")
            appendLine("| # | State | Ingestion (ms) | Quads | Cold Start (ms) | Query (ms) | Embeddings |")
            appendLine("|---|-------|----------------|-------|-----------------|------------|------------|")
            result.allIterations.forEach {
                appendLine("| ${it.iteration} | ${it.state} | ${it.ingestionTimeMs} | ${it.quadsIngested} | ${it.coldStartMs} | ${it.queryLatencyMs} | ${it.embeddingCount} |")
            }
        })

        // CSV
        File(reportsDir, "dynamic_knowledge_$timestamp.csv").writeText(buildString {
            appendLine("iteration,state,ingestion_ms,quads,cold_start_ms,query_ms,embeddings,success")
            result.allIterations.forEach { appendLine("${it.iteration},${it.state},${it.ingestionTimeMs},${it.quadsIngested},${it.coldStartMs},${it.queryLatencyMs},${it.embeddingCount},${it.success}") }
        })

        // JSON
        File(reportsDir, "dynamic_knowledge_$timestamp.json").writeText(
            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapOf(
                "config" to mapOf(
                    "iterations" to config.iterations,
                    "warmupQueries" to config.warmupQueries,
                    "measuredQueries" to config.measuredQueries,
                    "imageLimit" to config.imageLimit,
                    "database" to config.dbConnectionString,
                    "dockerContainer" to config.postgresContainerName
                ),
                "materializedColdStartMs" to result.materializedColdStartMs,
                "materializedStats" to result.materializedStats,
                "dynamicStats" to result.dynamicStats,
                "inferenceTaxMs" to result.inferenceTaxMs,
                "inferenceTaxPercent" to result.inferenceTaxPercent,
                "storageSavingsBytes" to result.storageSavingsBytes,
                "storageSavingsKB" to result.storageSavingsKB,
                "iterations" to result.allIterations
            ))
        )

        println("Reports saved to: ${reportsDir.absolutePath}")
    }
}

