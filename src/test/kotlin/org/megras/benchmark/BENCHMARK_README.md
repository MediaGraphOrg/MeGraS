# LSC SPARQL Benchmark

Benchmarks SPARQL queries for the Lifelog Search Challenge (LSC) dataset.

## Architecture

The framework consists of three main components:

1. **BenchmarkConfig** - Configuration data class with LSC preset
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

1. **Add SPARQL queries**: Place your LSC SPARQL query files in `src/test/resources/lsc_sparql_queries/` with `.sparql` or `.rq` extension.

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
