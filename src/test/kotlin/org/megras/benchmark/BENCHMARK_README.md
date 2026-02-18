# MeGraS Benchmarks

This directory contains benchmark frameworks for measuring MeGraS performance.

## Available Benchmarks

1. **SPARQL Benchmark** - Measures SPARQL query performance
2. **Ingestion Benchmark** - Measures TSV ingestion performance over time
3. **Dynamic Knowledge Benchmark** - Compares materialized vs on-the-fly embedding generation
4. **Performance Analysis Suite** - Comprehensive engine optimization benchmarks:
   - Cost of Hybridity
   - Pushdown Effect
   - Graph Volume Scalability
   - Vector Dimensionality Scalability

## Architecture Overview

The benchmark framework is organized into:

```
benchmark/
├── core/                           # Shared utilities
│   ├── BenchmarkStatistics.kt      # Statistical calculations
│   ├── BenchmarkReportGenerator.kt # Report generation (MD, CSV, JSON)
│   ├── DatabaseOperations.kt       # Database reset, ingestion
│   ├── SparqlClient.kt             # SPARQL query execution
│   └── BaseBenchmarkRunner.kt      # Base classes and configs
├── performance/                    # Performance analysis benchmarks
│   ├── QueryTemplates.kt           # SPARQL query templates for MeGraS-SYNTH
│   ├── PerformanceBenchmarkRunner.kt
│   ├── CostOfHybridityBenchmark.kt
│   ├── PushdownEffectBenchmark.kt
│   ├── GraphVolumeScalabilityBenchmark.kt
│   ├── VectorDimensionalityBenchmark.kt
│   └── PerformanceAnalysisSuite.kt # Runs all performance benchmarks
├── SparqlBenchmarkRunner.kt        # Generic SPARQL benchmarking
├── IngestionBenchmarkRunner.kt     # Ingestion performance
├── DynamicKnowledgeBenchmark.kt    # Materialized vs dynamic embeddings
├── LscSparqlBenchmark.kt           # LSC dataset queries
└── PostgresIngestionBenchmark.kt   # PostgreSQL ingestion
```

---

# Performance Analysis Benchmarks

These benchmarks evaluate MeGraS engine-level optimizations using the MeGraS-SYNTH dataset.

## Quick Start

Run all performance benchmarks:
```bash
./gradlew test --tests "org.megras.benchmark.performance.PerformanceAnalysisSuite.runAll"
```

Run individual benchmarks:
```bash
./gradlew test --tests "org.megras.benchmark.performance.CostOfHybridityBenchmark"
./gradlew test --tests "org.megras.benchmark.performance.PushdownEffectBenchmark"
./gradlew test --tests "org.megras.benchmark.performance.GraphVolumeScalabilityBenchmark"
./gradlew test --tests "org.megras.benchmark.performance.VectorDimensionalityBenchmark"
```

## Cost of Hybridity Benchmark

Quantifies the overhead of combining symbolic patterns with vector similarity operators.

**Query Types:**
- Symbolic-only: Pure triple pattern matching
- Vector-only: Pure k-NN vector search
- Hybrid: Combined symbolic filter + vector search

**Metrics:** Execution latency, throughput, overhead percentage

**Output:** `benchmark_reports/performance/hybridity/`

## Pushdown Effect Benchmark

Evaluates Early Binding optimization by varying filter selectivity.

**Selectivity Levels:**
- 0.1% (sel001) - Very restrictive
- 1% (sel01) - Moderately restrictive
- 10% (sel10) - Less restrictive
- 50% (sel50) - Minimally restrictive

**Goal:** Prove stable performance as filters become more restrictive

**Output:** `benchmark_reports/performance/pushdown/`

## Graph Volume Scalability Benchmark

Tests how retrieval scales with increasing graph size.

**Dataset Volumes:**
- 10^5 triples (100k)
- 10^6 triples (1M)
- 10^7 triples (10M)

**Goal:** Demonstrate logarithmic scaling

**Output:** `benchmark_reports/performance/scalability/volume/`

## Vector Dimensionality Benchmark

Characterizes performance with varying embedding dimensions.

**Dimensions:** 256, 512, 768, 1024

**Goal:** Identify bottlenecks in high-dimensional vector processing

**Output:** `benchmark_reports/performance/scalability/dimensionality/`

---

# SPARQL Benchmark

Benchmarks SPARQL queries for an arbitrary dataset.

## Architecture

The framework consists of three main components:

1. **BenchmarkConfig** - Configuration data class
2. **SparqlBenchmarkRunner** - Generic benchmark execution engine
3. **LscSparqlBenchmark** - Test class that runs the LSC benchmark

## Warmup vs Warm Runs

The benchmark distinguishes between different types of executions:

| Type | Description | Measured |
|------|-------------|----------|
| **Cold Start** | First execution (realistic first-time latency) | Reported separately |
| **Warmup Runs** | Initial executions to warm up JVM/caches | No |
| **Warm Runs** | Timed executions after warmup | Yes |

- If `warmupRuns > 0`: Cold start is the first warmup run
- If `warmupRuns = 0`: Cold start is the first warm run (also included in warm statistics)

## How to Use

1. **Add SPARQL queries**: Place your SPARQL query files in `src/test/resources/lsc_sparql_queries/` with `.sparql` or `.rq` extension.

2. **Start the MeGraS server**: The benchmark requires the server running at `http://localhost:8080`.

3. **Run the benchmark**:
   ```bash
   ./gradlew test --tests "org.megras.benchmark.LscSparqlBenchmark"
   ```

4. **View results**: Reports are saved in `benchmark_reports/lsc/` in three formats:
   - Markdown (`.md`) - Human-readable report
   - CSV (`.csv`) - For spreadsheet import
   - JSON (`.json`) - For programmatic access

## Configuration Options

Modify constants in `LscSparqlBenchmark.kt`:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `BASE_URL` | `http://localhost:8080/query/sparql` | SPARQL endpoint URL |
| `WARMUP_RUNS` | 3 | Number of warmup runs (not measured) |
| `WARM_RUNS` | 10 | Number of timed runs after warmup |

## Metrics Collected

For each query, the benchmark reports:

- **Cold Start**: First execution time (realistic user experience)
- **Min/Max**: Range of response times (warm runs)
- **Mean**: Average response time
- **Median**: Middle value (robust to outliers)
- **Std Dev**: Consistency measure (lower = more consistent)
- **Result Count**: Number of results returned
- **Success Rate**: Successful runs / total runs

## Adding New Use Cases

To add a new benchmark for a different dataset:

1. Create a new queries directory: `src/test/resources/mydata_sparql_queries/`

2. Add a factory method to `BenchmarkConfig`:
   ```kotlin
   companion object {
       fun myData(baseUrl: String = "...", warmupRuns: Int = 3, warmRuns: Int = 10) =
           BenchmarkConfig(
               name = "My Data SPARQL Benchmark",
               baseUrl = baseUrl,
               queriesDir = "src/test/resources/mydata_sparql_queries",
               reportsDir = "benchmark_reports/mydata",
               warmupRuns = warmupRuns,
               warmRuns = warmRuns
           )
   }
   ```

3. Create a test class:
   ```kotlin
   class MyDataSparqlBenchmark {
       @Test
       fun runMyDataBenchmark() {
           val config = BenchmarkConfig.myData()
           val runner = SparqlBenchmarkRunner(config)
           runner.runBenchmark()
       }
   }
   ```

---

# PostgreSQL Ingestion Benchmark

Measures how ingestion performance changes as the number of items in the database increases.

## Purpose

This benchmark helps identify:
- Performance degradation patterns during bulk data loading
- How database size affects ingestion speed
- Optimal batch sizes for your data

## Architecture

The framework consists of two main components:

1. **IngestionBenchmarkConfig** - Configuration data class
2. **IngestionBenchmarkRunner** - Benchmark execution engine

## How to Use

1. **Prepare a TSV file**: Place your TSV file in the project root directory (or configure the path).

2. **Ensure PostgreSQL is running**: The benchmark requires a PostgreSQL database.

3. **Run the benchmark**:
   ```bash
   ./gradlew test --tests "org.megras.benchmark.PostgresIngestionBenchmark"
   ```

4. **View results**: Reports are saved in `benchmark_reports/ingestion/` in three formats:
   - Markdown (`.md`) - Human-readable report with ASCII chart
   - CSV (`.csv`) - For spreadsheet import and graphing
   - JSON (`.json`) - For programmatic access

## Configuration Options

Modify parameters in `PostgresIngestionBenchmark.kt`:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `tsvFile` | `quads.tsv` | TSV file to ingest (from project root) |
| `dbHost` | `localhost` | PostgreSQL host |
| `dbPort` | `5432` | PostgreSQL port |
| `dbName` | `megras` | PostgreSQL database name |
| `dbUser` | `megras` | Database username |
| `dbPassword` | `megras` | Database password |
| `batchSize` | `1000` | Items per batch insert AND measurement interval |
| `clearBeforeRun` | `true` | Clear database before starting |
| `skipLines` | `1` | Lines to skip (e.g., header) |

## Example Configuration

```kotlin
private val config = IngestionBenchmarkConfig(
    name = "PostgreSQL Ingestion Benchmark",
    tsvFile = "quads.tsv",
    reportsDir = "benchmark_reports/ingestion",
    dbHost = "localhost",
    dbPort = 5432,
    dbName = "megras",
    dbUser = "megras",
    dbPassword = "megras",
    batchSize = 1000,  // Batch size AND measurement interval
    clearBeforeRun = true,
    skipLines = 1
)
```

## Metrics Collected

For each measurement interval, the benchmark reports:

- **Items Processed**: Total items ingested so far
- **Elapsed Time**: Total time since benchmark started
- **Interval Time**: Time for this interval
- **Items/Second**: Ingestion rate for this interval

Summary statistics include:
- **Overall Rate**: Total items / total time
- **Min/Max Rate**: Range of interval rates
- **Mean/Median Rate**: Average and middle rates
- **Std Dev**: Consistency measure

## Sample Output

```
================================================================================
PostgreSQL Ingestion Benchmark
================================================================================
TSV file: C:\path\to\quads.tsv
Database: localhost:5432/megras
Batch size (and measurement interval): 1000
Clear before run: true

Clearing database...
Database cleared.

--------------------------------------------------------------------------------
Starting ingestion...
--------------------------------------------------------------------------------
  10000 items: 5234.12 items/sec (interval: 1911ms)
  20000 items: 4892.45 items/sec (interval: 2044ms)
  30000 items: 4567.89 items/sec (interval: 2189ms)
  ...

Ingestion completed: 100000 items in 21234ms
```

## Understanding Results

The key insight from this benchmark is how ingestion speed changes as the database grows:

- **Constant rate**: Good - database handles growth well
- **Declining rate**: Expected - indexes and constraints add overhead
- **Sharp decline**: May indicate configuration issues

---
