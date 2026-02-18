package org.megras.benchmark.performance

import org.junit.jupiter.api.Test
import org.megras.benchmark.core.*

/**
 * Performance Analysis Benchmark Suite
 *
 * A unified test suite that runs all performance benchmarks for MeGraS engine optimization analysis.
 *
 * This suite covers:
 * 1. Cost of Hybridity - Measures overhead of joint vector-symbolic execution
 * 2. Pushdown Effect - Evaluates Early Binding optimization effectiveness
 * 3. Graph Volume Scalability - Tests scaling with increasing graph size
 * 4. Vector Dimensionality Scalability - Tests scaling with vector dimensions
 *
 * Run all benchmarks: ./gradlew test --tests "org.megras.benchmark.performance.PerformanceAnalysisSuite"
 * Run individual: ./gradlew test --tests "org.megras.benchmark.performance.*Benchmark"
 */
class PerformanceAnalysisSuite {

    companion object {
        private const val DEFAULT_REPORTS_DIR = "benchmark_reports/performance"
    }

    private val baseConfig = PerformanceBenchmarkConfig(
        name = "Performance Analysis Suite",
        reportsDir = DEFAULT_REPORTS_DIR,
        warmupRuns = 3,
        measuredRuns = 10,
        k = 10
    )

    /**
     * Run all performance benchmarks in sequence.
     */
    @Test
    fun runAll() {
        println("=".repeat(80))
        println("PERFORMANCE ANALYSIS BENCHMARK SUITE")
        println("=".repeat(80))
        println()
        println("This suite will run the following benchmarks:")
        println("  1. Cost of Hybridity")
        println("  2. Pushdown Effect")
        println("  3. Graph Volume Scalability")
        println("  4. Vector Dimensionality Scalability")
        println()

        val results = mutableMapOf<String, Any>()

        // 1. Cost of Hybridity
        println("\n" + "=".repeat(80))
        println("BENCHMARK 1/4: Cost of Hybridity")
        println("=".repeat(80))
        try {
            val hybridityConfig = baseConfig.copy(
                name = "Cost of Hybridity Benchmark",
                reportsDir = "$DEFAULT_REPORTS_DIR/hybridity"
            )
            val hybridityResult = CostOfHybridityRunner(hybridityConfig).runBenchmark()
            results["hybridity"] = hybridityResult
        } catch (e: Exception) {
            println("ERROR in Cost of Hybridity: ${e.message}")
            results["hybridity"] = "FAILED: ${e.message}"
        }

        // 2. Pushdown Effect
        println("\n" + "=".repeat(80))
        println("BENCHMARK 2/4: Pushdown Effect")
        println("=".repeat(80))
        try {
            val pushdownConfig = baseConfig.copy(
                name = "Pushdown Effect Benchmark",
                reportsDir = "$DEFAULT_REPORTS_DIR/pushdown"
            )
            val pushdownResult = PushdownEffectRunner(pushdownConfig).runBenchmark()
            results["pushdown"] = pushdownResult
        } catch (e: Exception) {
            println("ERROR in Pushdown Effect: ${e.message}")
            results["pushdown"] = "FAILED: ${e.message}"
        }

        // 3. Graph Volume Scalability
        println("\n" + "=".repeat(80))
        println("BENCHMARK 3/4: Graph Volume Scalability")
        println("=".repeat(80))
        try {
            val volumeConfig = baseConfig.copy(
                name = "Graph Volume Scalability Benchmark",
                reportsDir = "$DEFAULT_REPORTS_DIR/scalability/volume"
            )
            val volumeResult = GraphVolumeScalabilityRunner(
                volumeConfig,
                GraphVolumeScalabilityBenchmark.VOLUME_CONFIGS
            ).runBenchmark()
            results["volume"] = volumeResult
        } catch (e: Exception) {
            println("ERROR in Graph Volume Scalability: ${e.message}")
            results["volume"] = "FAILED: ${e.message}"
        }

        // 4. Vector Dimensionality Scalability
        println("\n" + "=".repeat(80))
        println("BENCHMARK 4/4: Vector Dimensionality Scalability")
        println("=".repeat(80))
        try {
            val dimConfig = baseConfig.copy(
                name = "Vector Dimensionality Scalability Benchmark",
                reportsDir = "$DEFAULT_REPORTS_DIR/scalability/dimensionality"
            )
            val dimResult = VectorDimensionalityRunner(
                dimConfig,
                VectorDimensionalityBenchmark.DIMENSIONALITY_CONFIGS
            ).runBenchmark()
            results["dimensionality"] = dimResult
        } catch (e: Exception) {
            println("ERROR in Vector Dimensionality: ${e.message}")
            results["dimensionality"] = "FAILED: ${e.message}"
        }

        // Final Summary
        println("\n" + "=".repeat(80))
        println("SUITE COMPLETE")
        println("=".repeat(80))
        println()
        println("Results Summary:")
        for ((benchmark, result) in results) {
            val status = if (result is String && result.startsWith("FAILED")) "❌ $result" else "✓ Complete"
            println("  $benchmark: $status")
        }
        println()
        println("Reports saved to: $DEFAULT_REPORTS_DIR/")
    }

    /**
     * Run only the Cost of Hybridity benchmark.
     */
    @Test
    fun runCostOfHybridity() {
        CostOfHybridityBenchmark().run()
    }

    /**
     * Run only the Pushdown Effect benchmark.
     */
    @Test
    fun runPushdownEffect() {
        PushdownEffectBenchmark().run()
    }

    /**
     * Run only the Graph Volume Scalability benchmark.
     */
    @Test
    fun runGraphVolumeScalability() {
        GraphVolumeScalabilityBenchmark().run()
    }

    /**
     * Run only the Vector Dimensionality benchmark.
     */
    @Test
    fun runVectorDimensionality() {
        VectorDimensionalityBenchmark().run()
    }
}

