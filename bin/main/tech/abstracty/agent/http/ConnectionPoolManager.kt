package tech.abstracty.agent.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders.ContentEncoding
import io.ktor.serialization.kotlinx.json.json
import io.netty.handler.codec.compression.StandardCompressionOptions.deflate
import io.netty.handler.codec.compression.StandardCompressionOptions.gzip
import kotlinx.serialization.json.Json
import java.net.http.HttpClient as JHttpClient
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object ConnectionPoolManager {
    private val httpClients = ConcurrentHashMap<String, JHttpClient>()
    private val ktorClients = ConcurrentHashMap<String, HttpClient>()
    private val executors = ConcurrentHashMap<String, ExecutorService>()

    fun getHttpClient(poolSize: Int = 10): JHttpClient {
        return httpClients.computeIfAbsent("pool-$poolSize") {
            val executor = Executors.newFixedThreadPool(poolSize)
            executors["pool-$poolSize"] = executor
            JHttpClient.newBuilder()
                .version(JHttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(30))
                .executor(executor)
                .followRedirects(JHttpClient.Redirect.NORMAL)
                .build()
        }
    }

    fun getKtorClient(): HttpClient {
        return ktorClients.computeIfAbsent("default") {
            HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }

                install(ContentEncoding) {
                    gzip()
                    deflate()
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
                }
            }
        }
    }

    fun shutdown() {
        // Shutdown Ktor clients
        ktorClients.values.forEach { client ->
            try {
                client.close()
            } catch (_: Exception) {}
        }
        ktorClients.clear()

        // Shutdown executor services for Java HttpClients
        executors.values.forEach { executor ->
            try {
                executor.shutdown()
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow()
                }
            } catch (_: Exception) {
                executor.shutdownNow()
            }
        }
        executors.clear()
        httpClients.clear()
    }
}
