package tech.abstracty.agent.rag

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Global connection pool manager for efficient client reuse.
 *
 * Provides pooled Qdrant and HTTP clients with caching by key.
 */
object ConnectionPool {
    private val qdrantClients = ConcurrentHashMap<String, QdrantClient>()
    private val httpClients = ConcurrentHashMap<String, java.net.http.HttpClient>()
    private val ktorClients = ConcurrentHashMap<String, HttpClient>()
    private val tenantSemaphores = ConcurrentHashMap<String, Semaphore>()

    /**
     * Get or create a Qdrant client for the given connection parameters.
     */
    fun getQdrantClient(tenantId: String, host: String, port: Int): QdrantClient {
        return qdrantClients.computeIfAbsent("$tenantId:$host:$port") {
            QdrantClient(
                QdrantGrpcClient
                    .newBuilder(host, port, false)
                    .build()
            )
        }
    }

    /**
     * Get or create a Java HTTP client with the specified pool size.
     */
    fun getHttpClient(poolSize: Int = 10): java.net.http.HttpClient {
        return httpClients.computeIfAbsent("pool-$poolSize") {
            java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_2)
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .executor(java.util.concurrent.Executors.newFixedThreadPool(poolSize))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build()
        }
    }

    /**
     * Get or create a Ktor HTTP client.
     */
    fun getKtorClient(): HttpClient {
        return ktorClients.computeIfAbsent("default") {
            HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    })
                }

                expectSuccess = false

                install(HttpTimeout) {
                    requestTimeoutMillis = 15000
                    connectTimeoutMillis = 8000
                    socketTimeoutMillis = 15000
                }

                engine {
                    pipelining = true
                    maxConnectionsCount = 100
                    endpoint {
                        maxConnectionsPerRoute = 10
                        pipelineMaxSize = 20
                        keepAliveTime = 5000
                        connectTimeout = 5000
                        connectAttempts = 5
                    }
                }
            }
        }
    }

    /**
     * Get or create a semaphore for rate limiting a tenant.
     */
    fun getTenantSemaphore(tenantId: String, permits: Int = 10): Semaphore {
        return tenantSemaphores.computeIfAbsent(tenantId) {
            Semaphore(permits)
        }
    }

    /**
     * Shutdown all pooled clients.
     * Call this when the application is shutting down.
     */
    fun shutdown() {
        qdrantClients.values.forEach { client ->
            runCatching { client.close() }
        }
        ktorClients.values.forEach { client ->
            runCatching { client.close() }
        }

        qdrantClients.clear()
        httpClients.clear()
        ktorClients.clear()
        tenantSemaphores.clear()
    }
}
