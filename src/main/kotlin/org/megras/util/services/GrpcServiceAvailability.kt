package org.megras.util.services

import io.grpc.ConnectivityState
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import org.megras.util.ServiceConfig
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Singleton service that tracks availability of gRPC services.
 * Caches availability status to avoid repeated connection attempts.
 */
object GrpcServiceAvailability {

    private val logger = LoggerFactory.getLogger(GrpcServiceAvailability::class.java)

    // Cache TTL in milliseconds (5 minutes)
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    // Timeout for checking connectivity (in seconds)
    private const val CHECK_TIMEOUT_SECONDS = 2L

    private data class AvailabilityStatus(
        val isAvailable: Boolean,
        val timestamp: Long
    )

    private val statusCache = ConcurrentHashMap<String, AvailabilityStatus>()

    /**
     * Check if the gRPC service is available.
     * Results are cached for CACHE_TTL_MS to avoid repeated connection attempts.
     *
     * @return true if the service is available, false otherwise
     */
    fun isServiceAvailable(): Boolean {
        val cacheKey = "${ServiceConfig.grpcHost}:${ServiceConfig.grpcPort}"
        val now = System.currentTimeMillis()

        // Check cache first
        val cached = statusCache[cacheKey]
        if (cached != null && (now - cached.timestamp) < CACHE_TTL_MS) {
            return cached.isAvailable
        }

        // Perform availability check
        val isAvailable = checkServiceAvailability()

        // Update cache
        statusCache[cacheKey] = AvailabilityStatus(isAvailable, now)

        if (!isAvailable) {
            logger.warn("gRPC service at $cacheKey is not available. Derived relations requiring gRPC will be skipped.")
        }

        return isAvailable
    }

    /**
     * Perform actual connectivity check to the gRPC service.
     */
    private fun checkServiceAvailability(): Boolean {
        var channel: ManagedChannel? = null
        return try {
            channel = ManagedChannelBuilder
                .forAddress(ServiceConfig.grpcHost, ServiceConfig.grpcPort)
                .usePlaintext()
                .executor(Dispatchers.Default.asExecutor())
                .build()

            // Request connection and wait for state change
            channel.getState(true) // requestConnection = true

            // Wait for the channel to transition from IDLE to READY or fail
            val deadline = System.currentTimeMillis() + (CHECK_TIMEOUT_SECONDS * 1000)
            while (System.currentTimeMillis() < deadline) {
                val state = channel.getState(false)
                when (state) {
                    ConnectivityState.READY -> return true
                    ConnectivityState.TRANSIENT_FAILURE,
                    ConnectivityState.SHUTDOWN -> return false
                    ConnectivityState.IDLE,
                    ConnectivityState.CONNECTING -> {
                        // Wait a bit and check again
                        Thread.sleep(100)
                    }
                }
            }

            // Timeout - consider unavailable
            false
        } catch (e: Exception) {
            logger.debug("Exception while checking gRPC service availability: ${e.message}")
            false
        } finally {
            try {
                channel?.shutdown()?.awaitTermination(1, TimeUnit.SECONDS)
            } catch (e: Exception) {
                // Ignore shutdown errors
            }
        }
    }

    /**
     * Invalidate the cache entry for the current service configuration.
     * Useful when the user wants to retry connecting to a service.
     */
    fun invalidateCache() {
        val cacheKey = "${ServiceConfig.grpcHost}:${ServiceConfig.grpcPort}"
        statusCache.remove(cacheKey)
        logger.info("Invalidated gRPC availability cache for $cacheKey")
    }

    /**
     * Clear all cached availability statuses.
     */
    fun clearCache() {
        statusCache.clear()
        logger.info("Cleared all gRPC availability cache entries")
    }
}

