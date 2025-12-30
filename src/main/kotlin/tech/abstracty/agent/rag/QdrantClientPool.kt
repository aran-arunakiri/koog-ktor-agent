package tech.abstracty.agent.rag

import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import java.util.concurrent.ConcurrentHashMap

object QdrantClientPool {
    private val qdrantClients = ConcurrentHashMap<String, QdrantClient>()

    fun getClient(tenantId: String, host: String, port: Int): QdrantClient {
        return qdrantClients.computeIfAbsent("$tenantId:$host:$port") {
            QdrantClient(
                QdrantGrpcClient
                    .newBuilder(host, port, false)
                    .build()
            )
        }
    }

    fun shutdown() {
        qdrantClients.values.forEach { client ->
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
        qdrantClients.clear()
    }
}
