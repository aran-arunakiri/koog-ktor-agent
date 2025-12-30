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
import java.util.concurrent.Executors

object ConnectionPoolManager {
    private val httpClients = ConcurrentHashMap<String, JHttpClient>()
    private val ktorClients = ConcurrentHashMap<String, HttpClient>()

    fun getHttpClient(poolSize: Int = 10): JHttpClient {
        return httpClients.computeIfAbsent("pool-$poolSize") {
            JHttpClient.newBuilder()
                .version(JHttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(30))
                .executor(Executors.newFixedThreadPool(poolSize))
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
}
