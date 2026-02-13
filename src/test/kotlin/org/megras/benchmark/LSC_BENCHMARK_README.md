# LSC SPARQL Benchmark

This directory contains SPARQL query files (`.sparql` or `.rq` extension) for benchmarking the Lifelog Search Challenge (LSC) dataset queries.

## How to Use

1. **Add SPARQL queries**: Place your LSC SPARQL query files in this directory with either `.sparql` or `.rq` extension. Each file should contain exactly one SPARQL query.

2. **Start the MeGraS server**: The benchmark requires the server to be running at `http://localhost:8080` (or modify the `BASE_URL` in `LscSparqlBenchmark.kt`).

3. **Run the benchmark**: 
   
   Use the IntelliJ run configuration (click ▶ below):
   
   [Run LSC SPARQL Benchmark](ijidea://runConfiguration/LSC%20SPARQL%20Benchmark)
   
   Or run from terminal (from project root):
   ```bash
   ./gradlew test --tests "org.megras.benchmark.LscSparqlBenchmark"
   ```

4. **View results**: Reports are saved in the `benchmark_reports/lsc/` directory in three formats:
   - Markdown (`.md`) - Human-readable report
   - CSV (`.csv`) - For spreadsheet import
   - JSON (`.json`) - For programmatic access

## Query File Format

Each `.sparql` or `.rq` file should contain a single SPARQL query. The query can be on a single line or formatted with line breaks.

### Example Query (example_car_date_query.sparql):

```sparql
PREFIX lsc: <http://lsc.dcu.ie/schema#>
PREFIX tag: <http://lsc.dcu.ie/tag#>
PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>

SELECT DISTINCT ?img ?id ?day 
WHERE {
  ?img lsc:id ?id .
  {
    ?img lsc:tag tag:car .
  }
  {
    ?img lsc:day ?day .
    BIND(xsd:date(STRAFTER(STR(?day), "#")) AS ?dayDate)
    FILTER ((?dayDate >= "2020-06-23"^^xsd:date) && (?dayDate <= "2020-06-30"^^xsd:date))
  }
}
ORDER BY ?id
```

## Configuration

The benchmark can be configured by modifying the companion object constants in `LscSparqlBenchmark.kt`:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `BASE_URL` | `http://localhost:8080/query/sparql` | SPARQL endpoint URL |
| `WARMUP_RUNS` | 3 | Number of warmup iterations (not counted in stats) |
| `BENCHMARK_RUNS` | 10 | Number of timed iterations per query |
| `CONNECT_TIMEOUT_MS` | 30000 | HTTP connection timeout |
| `READ_TIMEOUT_MS` | 60000 | HTTP read timeout |
| `REPORTS_DIR` | `benchmark_reports/lsc` | Output directory for LSC reports |

## Metrics Collected

For each query, the benchmark calculates:

- **Min**: Minimum response time
- **Max**: Maximum response time
- **Mean**: Average response time
- **Median**: Middle value of sorted response times
- **Std Dev**: Standard deviation (measure of consistency)
- **Result Count**: Number of results returned by the query
- **Success Rate**: Number of successful runs vs total runs

