package org.megras.benchmark

import org.junit.jupiter.api.Test
import org.megras.benchmark.core.SparqlClient
import org.megras.benchmark.performance.InfrastructureConfig
import org.megras.benchmark.performance.MegrasServerConfig
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * LSC SPARQL Benchmark
 *
 * Benchmarks SPARQL queries for the Lifelog Search Challenge (LSC) dataset.
 * The MeGraS server configuration (database backend, query engine, etc.) is
 * specified via [MegrasServerConfig], consistent with the performance benchmarks.
 *
 * Infrastructure (PostgreSQL Docker container and MeGraS) is automatically
 * (re)started before the benchmark and stopped after.
 */
class LscSparqlBenchmark {

    companion object {
        private const val DEFAULT_QUERIES_DIR = "src/test/resources/lsc_sparql_queries"
        private const val DEFAULT_REPORTS_DIR = "benchmark_reports/lsc"
        private const val DEFAULT_WARMUP_RUNS = 3
        private const val DEFAULT_WARM_RUNS = 10
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 30000
        private const val DEFAULT_READ_TIMEOUT_MS = 60000
    }

    private val megrasServerConfig = MegrasServerConfig(
        objectStoreBase = "store",
        httpPort = 8080,
        backend = "POSTGRES",
        postgresHost = "localhost",
        postgresPort = 5432,
        postgresDatabase = "lsc25",
        postgresUser = "megras",
        postgresPassword = "megras",
        sparqlQueryEngine = "BATCHING"
    )

    private val infraConfig = InfrastructureConfig(
        postgresContainerName = "megrasdb",
        autoRestart = true,
        megrasServerConfig = megrasServerConfig
    )

    private val benchmarkConfig = BenchmarkConfig(
        name = "LSC SPARQL Benchmark (backend=${megrasServerConfig.backend}, engine=${megrasServerConfig.sparqlQueryEngine})",
        baseUrl = "http://${megrasServerConfig.postgresHost}:${megrasServerConfig.httpPort}/query/sparql",
        queriesDir = DEFAULT_QUERIES_DIR,
        reportsDir = DEFAULT_REPORTS_DIR,
        warmupRuns = DEFAULT_WARMUP_RUNS,
        warmRuns = DEFAULT_WARM_RUNS,
        connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs = DEFAULT_READ_TIMEOUT_MS
    )

    // Track the MeGraS process if we start it
    private var megrasProcess: Process? = null

    @Test
    fun run() {
        try {
            println("=".repeat(80))
            println(benchmarkConfig.name)
            println("=".repeat(80))
            println()
            println("MeGraS Server Configuration:")
            println("  Backend:        ${megrasServerConfig.backend}")
            println("  Query Engine:   ${megrasServerConfig.sparqlQueryEngine}")
            println("  HTTP Port:      ${megrasServerConfig.httpPort}")
            println("  Object Store:   ${megrasServerConfig.objectStoreBase}")
            println("  Postgres:       ${megrasServerConfig.postgresHost}:${megrasServerConfig.postgresPort}/${megrasServerConfig.postgresDatabase}")
            println("  Auto-restart:   ${infraConfig.autoRestart}")
            println()

            restartInfrastructure("Starting benchmark with fresh infrastructure...")

            SparqlBenchmarkRunner(benchmarkConfig).runBenchmark()
        } catch (e: IllegalStateException) {
            println("ERROR: ${e.message}")
        } finally {
            stopInfrastructure()
        }
    }

    // ==================== Infrastructure Management ====================

    private fun restartInfrastructure(message: String = "Restarting infrastructure...") {
        if (!infraConfig.autoRestart) {
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
        stopPostgresContainer()

        // 3. Start PostgreSQL container
        startPostgresContainer()

        // 4. Start MeGraS
        val resolvedJarPath = infraConfig.getResolvedJarPath()
        if (resolvedJarPath != null) {
            startMegras(resolvedJarPath)
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

    private fun stopInfrastructure() {
        if (!infraConfig.autoRestart) {
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
        println("  Stopping PostgreSQL container: ${infraConfig.postgresContainerName}")
        val result = runCommand("docker", "stop", infraConfig.postgresContainerName)
        if (!result.first) {
            println("    Warning: Failed to stop container: ${result.second}")
        } else {
            println("    PostgreSQL container stopped.")
        }
    }

    private fun startPostgresContainer() {
        println("  Starting PostgreSQL container: ${infraConfig.postgresContainerName}")
        val result = runCommand("docker", "start", infraConfig.postgresContainerName)
        if (!result.first) {
            throw IllegalStateException("Failed to start PostgreSQL container: ${result.second}")
        }
        println("    PostgreSQL container started.")
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
            val findResult = runCommand("cmd", "/c", "netstat -ano | findstr :${megrasServerConfig.httpPort}")
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
            runCommand("sh", "-c", "fuser -k ${megrasServerConfig.httpPort}/tcp 2>/dev/null || lsof -ti:${megrasServerConfig.httpPort} | xargs kill -9 2>/dev/null || true")
        }

        Thread.sleep(2000)
        println("    MeGraS stopped (or was not running).")
    }

    private fun startMegras(jarPath: String) {
        println("  Starting MeGraS from: $jarPath")

        val jarFile = File(jarPath)
        if (!jarFile.exists()) {
            throw IllegalStateException("MeGraS JAR not found: $jarPath")
        }

        // Generate custom config file from MegrasServerConfig
        val configFile = megrasServerConfig.writeToTempFile()
        println("    Using custom config: ${configFile.absolutePath}")
        println("    Database: ${megrasServerConfig.postgresHost}:${megrasServerConfig.postgresPort}/${megrasServerConfig.postgresDatabase}")
        println("    Object Store: ${megrasServerConfig.objectStoreBase}")
        println("    Query Engine: ${megrasServerConfig.sparqlQueryEngine}")

        val command = mutableListOf("java")
        command.addAll(infraConfig.megrasJvmArgs)
        command.add("-jar")
        command.add(jarFile.absolutePath)
        command.add(configFile.absolutePath)

        val processBuilder = ProcessBuilder(command)
            .directory(File(infraConfig.megrasWorkDir))
            .redirectErrorStream(true)

        megrasProcess = processBuilder.start()

        // Consume output to prevent blocking
        Thread {
            megrasProcess?.inputStream?.bufferedReader()?.use { reader ->
                reader.lines().forEach { _ -> }
            }
        }.start()

        println("    MeGraS starting...")
    }

    private fun waitForEndpoint(maxWaitSeconds: Int = 60, pollIntervalMs: Long = 2000) {
        println("  Waiting for endpoint to be available...")
        val sparqlClient = SparqlClient(
            endpoint = benchmarkConfig.baseUrl,
            connectTimeoutMs = benchmarkConfig.connectTimeoutMs,
            readTimeoutMs = benchmarkConfig.readTimeoutMs
        )
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

    private fun waitForEnter() {
        System.out.flush()
        val console = System.console()
        if (console != null) {
            console.readLine()
            return
        }
        try {
            val scanner = java.util.Scanner(System.`in`)
            if (scanner.hasNextLine()) {
                scanner.nextLine()
            } else {
                println("  (No interactive input available, waiting 30 seconds...)")
                Thread.sleep(30000)
            }
        } catch (e: Exception) {
            println("  (Could not read input, waiting 30 seconds...)")
            Thread.sleep(30000)
        }
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
}
