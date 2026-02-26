package tech.abstracty.agent.rag

data class RagConfig(
    val tenantId: String,
    val qdrantHost: String = "localhost",
    val qdrantGrpcPort: Int = 6334,
    val openAIApiKey: String,
    val embeddingModel: String = "text-embedding-3-small",
    val topK: Int = 3,
    val searchConfig: SearchConfig = SearchConfig(),
    val connectionPoolConfig: ConnectionPoolConfig = ConnectionPoolConfig()
)

data class SearchConfig(
    val minScore: Float = 0.35f,
    val searchTimeoutSeconds: Long = 10
)

data class ConnectionPoolConfig(
    val useConnectionPool: Boolean = true
)
