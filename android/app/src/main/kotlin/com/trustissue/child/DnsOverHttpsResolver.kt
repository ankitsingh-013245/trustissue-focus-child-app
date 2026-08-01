package com.trustissue.child

import android.net.Network
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Reusable, network-aware DNS-over-HTTPS transport.
 *
 * A single HTTP/2-capable client is retained for the active physical network.
 * This avoids paying a new TCP/TLS setup cost for every browser DNS request.
 */
class DnsOverHttpsResolver(
    endpoints: List<String>,
    private val maximumResponseBytes: Int
) {
    private val endpoints = endpoints.filter { it.startsWith("https://") }
        .distinct()
        .also { require(it.isNotEmpty()) }

    data class Result(
        val response: ByteArray?,
        val attempts: Int,
        val elapsedMs: Long,
        val httpStatus: Int? = null,
        val resolverIndex: Int = 0,
        val error: String = "",
        val attemptTrace: String = ""
    )

    private val lock = Any()
    @Volatile
    private var boundNetwork: Network? = null
    @Volatile
    private var client: OkHttpClient = buildClient(null)

    fun updateNetwork(network: Network?) {
        synchronized(lock) {
            if (network == boundNetwork) return
            val previous = client
            boundNetwork = network
            client = buildClient(network)
            previous.dispatcher.cancelAll()
            previous.connectionPool.evictAll()
        }
    }

    fun query(message: ByteArray, maximumAttempts: Int = 2): Result {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var lastStatus: Int? = null
        var lastError = ""
        var lastResolverIndex = 0
        val attemptTrace = mutableListOf<String>()
        val attempts = maximumAttempts.coerceIn(1, endpoints.size)

        for (attempt in 1..attempts) {
            val attemptStartedAt = android.os.SystemClock.elapsedRealtime()
            val currentClient = client
            val resolverIndex = (attempt - 1) % endpoints.size
            lastResolverIndex = resolverIndex
            val request = Request.Builder()
                .url(endpoints[resolverIndex])
                .header("Accept", dnsMessageMediaType.toString())
                .header("Cache-Control", "no-store")
                .post(message.toRequestBody(dnsMessageMediaType))
                .build()
            try {
                currentClient.newCall(request).execute().use { response ->
                    lastStatus = response.code
                    if (!response.isSuccessful) {
                        lastError = "http_${response.code}"
                        attemptTrace +=
                            "$resolverIndex:$lastError:" +
                            "${android.os.SystemClock.elapsedRealtime() - attemptStartedAt}"
                    } else {
                        val body = response.body.bytes()
                        if (body.size in 12..maximumResponseBytes) {
                            attemptTrace +=
                                "$resolverIndex:ok:" +
                                "${android.os.SystemClock.elapsedRealtime() - attemptStartedAt}"
                            return Result(
                                response = body,
                                attempts = attempt,
                                elapsedMs =
                                    android.os.SystemClock.elapsedRealtime() - startedAt,
                                httpStatus = response.code,
                                resolverIndex = resolverIndex,
                                attemptTrace = attemptTrace.joinToString(",")
                            )
                        }
                        lastError = if (body.size < 12) {
                            "response_too_short"
                        } else {
                            "response_too_large"
                        }
                        attemptTrace +=
                            "$resolverIndex:$lastError:" +
                            "${android.os.SystemClock.elapsedRealtime() - attemptStartedAt}"
                    }
                }
            } catch (error: IOException) {
                lastError = error.javaClass.simpleName.ifBlank { "io_error" }
                attemptTrace +=
                    "$resolverIndex:$lastError:" +
                    "${android.os.SystemClock.elapsedRealtime() - attemptStartedAt}"
            } catch (error: RuntimeException) {
                lastError = error.javaClass.simpleName.ifBlank { "runtime_error" }
                attemptTrace +=
                    "$resolverIndex:$lastError:" +
                    "${android.os.SystemClock.elapsedRealtime() - attemptStartedAt}"
            }

            if (attempt < attempts) {
                // A broken pooled socket should never poison the explicit retry.
                currentClient.connectionPool.evictAll()
            }
        }

        return Result(
            response = null,
            attempts = attempts,
            elapsedMs = android.os.SystemClock.elapsedRealtime() - startedAt,
            httpStatus = lastStatus,
            resolverIndex = lastResolverIndex,
            error = lastError.ifBlank { "unknown_failure" },
            attemptTrace = attemptTrace.joinToString(",")
        )
    }

    fun close() {
        synchronized(lock) {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        }
    }

    private fun buildClient(network: Network?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(
                ConnectionPool(
                    maximumIdleConnections,
                    keepAliveMinutes,
                    TimeUnit.MINUTES
                )
            )
        if (network != null) {
            builder.socketFactory(network.socketFactory)
            builder.dns(
                Dns { hostname ->
                    network.getAllByName(hostname).toList()
                }
            )
        }
        return builder.build()
    }

    companion object {
        private val dnsMessageMediaType = "application/dns-message".toMediaType()
        private const val connectTimeoutSeconds = 4L
        private const val readTimeoutSeconds = 6L
        private const val writeTimeoutSeconds = 4L
        private const val callTimeoutSeconds = 8L
        private const val maximumIdleConnections = 4
        private const val keepAliveMinutes = 5L
    }
}
