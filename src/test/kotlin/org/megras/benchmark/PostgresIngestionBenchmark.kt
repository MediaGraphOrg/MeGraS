package org.megras.benchmark

import org.junit.jupiter.api.Test

/**
 * PostgreSQL Ingestion Benchmark
 *
 * Measures how ingestion performance changes as the number of items in the database increases.
 * This helps identify performance degradation patterns during bulk data loading.
 */
class PostgresIngestionBenchmark {

    private val config = IngestionBenchmarkConfig(
        name = "PostgreSQL Ingestion Benchmark",
        tsvFile = "MeGraS-SYNTH-inflated.tsv",  // TSV file in the root directory
        reportsDir = "benchmark_reports/ingestion",
        dbHost = "localhost",
        dbPort = 5432,
        dbName = "megras_benchmark",
        dbUser = "megras",
        dbPassword = "megras",
        batchSize = 10000,  // Batch size AND measurement interval
        clearBeforeRun = true,
        skipLines = 1  // Skip header line
    )

    @Test
    fun run() {
        try {
            IngestionBenchmarkRunner(config).runBenchmark()
        } catch (e: IllegalStateException) {
            println("ERROR: ${e.message}")
        }
    }
}

