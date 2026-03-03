package org.megras.benchmark.core

import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.graphstore.db.PostgresStore
import java.io.File
import java.sql.DriverManager

/**
 * Common database operations for benchmarks.
 * Handles database reset, ingestion, and connection management.
 */
object DatabaseOperations {

    private val TSV_SPLITTER = "\t(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)".toRegex()

    /**
     * Database connection configuration.
     */
    data class DbConfig(
        val host: String = "localhost",
        val port: Int = 5432,
        val dbName: String = "megras",
        val user: String = "megras",
        val password: String = "megras"
    ) {
        val connectionString: String get() = "$host:$port/$dbName"
        val adminConnectionString: String get() = "$host:$port/postgres"
        val jdbcUrl: String get() = "jdbc:postgresql://$connectionString"
        val adminJdbcUrl: String get() = "jdbc:postgresql://$adminConnectionString"
    }

    /**
     * Drops and recreates the database for a clean slate.
     * Uses raw JDBC with autocommit since DROP/CREATE DATABASE cannot run in a transaction.
     */
    fun resetDatabase(config: DbConfig) {
        DriverManager.getConnection(
            config.adminJdbcUrl,
            config.user,
            config.password
        ).use { conn ->
            conn.autoCommit = true // Required for DROP/CREATE DATABASE

            // Terminate existing connections to the database
            conn.createStatement().use { stmt ->
                stmt.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '${config.dbName}' AND pid <> pg_backend_pid();")
            }

            conn.createStatement().use { stmt ->
                stmt.execute("DROP DATABASE IF EXISTS ${config.dbName};")
            }

            conn.createStatement().use { stmt ->
                stmt.execute("CREATE DATABASE ${config.dbName} WITH OWNER ${config.user};")
            }

            conn.createStatement().use { stmt ->
                stmt.execute("GRANT ALL PRIVILEGES ON DATABASE ${config.dbName} TO ${config.user};")
            }
        }
    }

    /**
     * Creates a fresh PostgresStore instance.
     */
    fun createStore(config: DbConfig): PostgresStore {
        return PostgresStore(
            host = config.connectionString,
            user = config.user,
            password = config.password
        ).also { it.setup() }
    }

    /**
     * Result of a TSV ingestion operation.
     */
    data class IngestionResult(
        val quadsIngested: Long,
        val durationMs: Long,
        val success: Boolean,
        val errorMessage: String? = null
    ) {
        val quadsPerSecond: Double
            get() = if (durationMs > 0) quadsIngested * 1000.0 / durationMs else 0.0
    }

    /**
     * Ingests a TSV file into the database.
     *
     * @param config Database configuration
     * @param tsvPath Path to the TSV file
     * @param skipLines Number of header lines to skip (default: 1)
     * @param batchSize Number of quads to batch before inserting (default: 10000)
     * @param progressCallback Optional callback for progress updates
     * @return IngestionResult with statistics
     */
    fun ingestTsv(
        config: DbConfig,
        tsvPath: String,
        skipLines: Int = 1,
        batchSize: Int = 10000,
        progressCallback: ((Long) -> Unit)? = null
    ): IngestionResult {
        val startTime = System.currentTimeMillis()

        return try {
            val store = createStore(config)
            val batch = mutableSetOf<Quad>()
            var totalQuads = 0L
            var skip = skipLines

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

                    if (batch.size >= batchSize) {
                        store.addAll(batch)
                        batch.clear()
                        progressCallback?.invoke(totalQuads)
                    }
                }
            }

            if (batch.isNotEmpty()) {
                store.addAll(batch)
            }

            IngestionResult(
                quadsIngested = totalQuads,
                durationMs = System.currentTimeMillis() - startTime,
                success = true
            )
        } catch (e: Exception) {
            IngestionResult(
                quadsIngested = 0,
                durationMs = System.currentTimeMillis() - startTime,
                success = false,
                errorMessage = e.message ?: e.javaClass.simpleName
            )
        }
    }

    /**
     * Ingests multiple TSV files sequentially.
     */
    fun ingestMultipleTsvFiles(
        config: DbConfig,
        tsvPaths: List<String>,
        skipLines: Int = 1,
        batchSize: Int = 10000,
        progressCallback: ((String, Long) -> Unit)? = null
    ): IngestionResult {
        val startTime = System.currentTimeMillis()
        var totalQuads = 0L

        return try {
            for (tsvPath in tsvPaths) {
                val result = ingestTsv(config, tsvPath, skipLines, batchSize) { quads ->
                    progressCallback?.invoke(tsvPath, quads)
                }
                if (!result.success) {
                    return IngestionResult(
                        quadsIngested = totalQuads,
                        durationMs = System.currentTimeMillis() - startTime,
                        success = false,
                        errorMessage = "Failed ingesting $tsvPath: ${result.errorMessage}"
                    )
                }
                totalQuads += result.quadsIngested
            }

            IngestionResult(
                quadsIngested = totalQuads,
                durationMs = System.currentTimeMillis() - startTime,
                success = true
            )
        } catch (e: Exception) {
            IngestionResult(
                quadsIngested = totalQuads,
                durationMs = System.currentTimeMillis() - startTime,
                success = false,
                errorMessage = e.message ?: e.javaClass.simpleName
            )
        }
    }

    /**
     * Finds a file in common locations.
     */
    fun findFile(filePath: String): File? {
        val possiblePaths = listOf(
            filePath,
            "../$filePath",
            filePath.substringAfterLast("/"),
            filePath.substringAfterLast("\\")
        )

        for (path in possiblePaths) {
            val file = File(path)
            if (file.exists() && file.isFile) {
                return file
            }
        }
        return null
    }

    /**
     * Finds a directory in common locations.
     */
    fun findDirectory(dirPath: String): File? {
        val possiblePaths = listOf(
            dirPath,
            "../$dirPath",
            dirPath.substringAfterLast("/"),
            dirPath.substringAfterLast("\\")
        )

        for (path in possiblePaths) {
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) {
                return dir
            }
        }
        return null
    }
}

