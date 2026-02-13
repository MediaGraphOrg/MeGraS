# LSC SPARQL Benchmark

Benchmarks SPARQL queries for the Lifelog Search Challenge (LSC) dataset.

## Warmup vs Warm Runs

The benchmark distinguishes between different types of executions:

| Type | Description | Measured |
|------|-------------|----------|
| **Cold Start** | First execution (realistic first-time latency) | Reported separately |
| **Warmup Runs** | Initial executions to warm up JVM/caches | No |
| **Warm Runs** | Timed executions after warmup | Yes |

- If `WARMUP_RUNS > 0`: Cold start is the first warmup run
- If `WARMUP_RUNS = 0`: Cold start is the first warm run (also included in warm statistics)

## How to Use

1. **Add SPARQL queries**: Place your LSC SPARQL query files in `src/test/resources/lsc_sparql_queries/` with `.sparql` or `.rq` extension.

2. **Start the MeGraS server**: The benchmark requires the server running at `http://localhost:8080`.

3. **Run the benchmark**: 
   
   From terminal (from project root):
   ```bash
   ./gradlew test --tests "org.megras.benchmark.LscSparqlBenchmark"
   ```

4. **View results**: Reports are saved in `benchmark_reports/lsc/` in three formats:
   - Markdown (`.md`) - Human-readable report
   - CSV (`.csv`) - For spreadsheet import
   - JSON (`.json`) - For programmatic access

## Configuration

Modify constants in `LscSparqlBenchmark.kt`:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `BASE_URL` | `http://localhost:8080/query/sparql` | SPARQL endpoint URL |
| `WARMUP_RUNS` | 3 | Number of warmup runs (not measured, for JVM/cache warmup) |
| `WARM_RUNS` | 10 | Number of timed runs after warmup |
| `CONNECT_TIMEOUT_MS` | 30000 | HTTP connection timeout |
| `READ_TIMEOUT_MS` | 60000 | HTTP read timeout |
| `REPORTS_DIR` | `benchmark_reports/lsc` | Output directory for LSC reports |


## Metrics Collected

For each query, the benchmark reports:

- **Cold Start**: First execution time (realistic user experience)
- **Min/Max**: Range of response times (warm runs)
- **Mean**: Average response time
- **Median**: Middle value (robust to outliers)
- **Std Dev**: Consistency measure (lower = more consistent)
- **Result Count**: Number of results returned
- **Success Rate**: Successful runs / total runs

