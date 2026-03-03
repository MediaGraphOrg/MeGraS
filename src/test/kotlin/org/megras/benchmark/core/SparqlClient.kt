package org.megras.benchmark.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * SPARQL query execution client for benchmarks.
 * Provides low-level query execution with detailed result tracking.
 */
class SparqlClient(
    private val endpoint: String,
    private val connectTimeoutMs: Int = 30000,
    private val readTimeoutMs: Int = 60000
) {
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    /**
     * Result of executing a SPARQL query.
     */
    data class QueryResult(
        val responseTimeMs: Long,
        val resultCount: Int,
        val success: Boolean,
        val errorMessage: String? = null,
        val httpStatusCode: Int? = null,
        val rawResponse: String? = null
    )

    /**
     * Checks if the SPARQL endpoint is available.
     */
    fun isAvailable(): Boolean {
        return try {
            val url = URL(endpoint)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"
            connection.connect()
            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode in 200..499
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Executes a SPARQL query and returns the result.
     */
    fun executeQuery(query: String, includeRawResponse: Boolean = false): QueryResult {
        val startTime = System.currentTimeMillis()

        return try {
            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
            val url = URL("$endpoint?query=$encodedQuery")

            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")

            val responseCode = connection.responseCode
            val responseTime = System.currentTimeMillis() - startTime

            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                val resultCount = parseResultCount(response)
                connection.disconnect()

                QueryResult(
                    responseTimeMs = responseTime,
                    resultCount = resultCount,
                    success = true,
                    httpStatusCode = responseCode,
                    rawResponse = if (includeRawResponse) response else null
                )
            } else {
                val errorStream = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                connection.disconnect()

                QueryResult(
                    responseTimeMs = responseTime,
                    resultCount = 0,
                    success = false,
                    errorMessage = "HTTP $responseCode: $errorStream",
                    httpStatusCode = responseCode
                )
            }
        } catch (e: Exception) {
            val responseTime = System.currentTimeMillis() - startTime
            QueryResult(
                responseTimeMs = responseTime,
                resultCount = 0,
                success = false,
                errorMessage = e.message ?: e.javaClass.simpleName
            )
        }
    }

    /**
     * Executes a query multiple times and returns all results.
     *
     * @param query The SPARQL query to execute
     * @param warmupRuns Number of warmup runs (not included in results)
     * @param measuredRuns Number of measured runs
     * @param progressCallback Optional callback for progress updates
     * @return Pair of (coldStartResult, measuredResults)
     */
    fun executeQueryWithWarmup(
        query: String,
        warmupRuns: Int,
        measuredRuns: Int,
        progressCallback: ((String, Int, QueryResult) -> Unit)? = null
    ): Pair<QueryResult?, List<QueryResult>> {
        var coldStart: QueryResult? = null
        val measuredResults = mutableListOf<QueryResult>()

        // Warmup runs
        repeat(warmupRuns) { i ->
            val result = executeQuery(query)
            if (i == 0) coldStart = result
            progressCallback?.invoke("warmup", i + 1, result)
        }

        // Measured runs
        repeat(measuredRuns) { i ->
            val result = executeQuery(query)
            if (coldStart == null && i == 0) coldStart = result
            measuredResults.add(result)
            progressCallback?.invoke("measured", i + 1, result)
        }

        return Pair(coldStart, measuredResults)
    }

    /**
     * Parse the result count from a SPARQL JSON response.
     */
    private fun parseResultCount(jsonResponse: String): Int {
        return try {
            val json = objectMapper.readTree(jsonResponse)
            val bindings = json.path("results").path("bindings")
            if (bindings.isArray) bindings.size() else 0
        } catch (e: Exception) {
            0
        }
    }
}

