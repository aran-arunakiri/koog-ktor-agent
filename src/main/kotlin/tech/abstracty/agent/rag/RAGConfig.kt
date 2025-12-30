package tech.abstracty.agent.rag

/**
 * Configuration for RAG (Retrieval-Augmented Generation) services.
 */
data class RAGConfig(
    /** Unique identifier for the tenant/application */
    val tenantId: String,

    /** Qdrant host address */
    val qdrantHost: String = "localhost",

    /** Qdrant gRPC port */
    val qdrantGrpcPort: Int = 6334,

    /** OpenAI API key for embeddings */
    val openAIApiKey: String,

    /** Embedding model name */
    val embeddingModel: EmbeddingModel = EmbeddingModel.TEXT_EMBEDDING_3_SMALL,

    /** Number of top results to return from vector search */
    val topK: Int = 3,

    /** Search configuration */
    val searchConfig: SearchConfig = SearchConfig(),

    /** Whether to use connection pooling */
    val useConnectionPool: Boolean = true,
)

/**
 * Search configuration for vector similarity search.
 */
data class SearchConfig(
    /** Minimum similarity score threshold */
    val minScore: Float = 0.35f,

    /** Relative score threshold (as percentage of top score) */
    val relativeScoreThreshold: Float = 0.75f,

    /** Search timeout in seconds */
    val searchTimeoutSeconds: Long = 10,
)

/**
 * Supported embedding models.
 */
enum class EmbeddingModel(val modelName: String, val dimensions: Int) {
    TEXT_EMBEDDING_ADA_002("text-embedding-ada-002", 1536),
    TEXT_EMBEDDING_3_SMALL("text-embedding-3-small", 1536),
    TEXT_EMBEDDING_3_LARGE("text-embedding-3-large", 3072);

    companion object {
        fun fromString(name: String): EmbeddingModel {
            return entries.find { it.modelName == name } ?: TEXT_EMBEDDING_3_SMALL
        }
    }
}
