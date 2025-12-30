package tech.abstracty.agent.rag

import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import io.qdrant.client.grpc.Points
import io.qdrant.client.grpc.Points.SearchPoints
import io.qdrant.client.grpc.Points.WithPayloadSelector
import kotlinx.coroutines.guava.await

/**
 * Type alias for collection search functions.
 */
typealias CollectionSearchFunction = suspend (query: String, collection: String) -> String

/**
 * Service for performing vector similarity searches in Qdrant.
 */
class VectorSearchService(
    private val config: RAGConfig,
) {
    private val embeddingService: EmbeddingService by lazy {
        EmbeddingService.fromConfig(config)
    }

    private val qdrantClient: QdrantClient by lazy {
        if (config.useConnectionPool) {
            ConnectionPool.getQdrantClient(config.tenantId, config.qdrantHost, config.qdrantGrpcPort)
        } else {
            QdrantClient(
                QdrantGrpcClient.newBuilder(config.qdrantHost, config.qdrantGrpcPort, false).build()
            )
        }
    }

    /**
     * Search in a specific collection.
     *
     * @param query The search query text
     * @param collectionName The Qdrant collection to search in
     * @return Formatted search results as a string
     */
    suspend fun search(query: String, collectionName: String): String {
        return try {
            val queryVector = embeddingService.embed(query)
            val searchResults = executeSearch(collectionName, queryVector)

            if (searchResults.isEmpty()) {
                return noResultsMessage(collectionName)
            }

            val filteredResults = filterByScore(searchResults)
            if (filteredResults.isEmpty()) {
                return noResultsMessage(collectionName)
            }

            formatResults(filteredResults)
        } catch (e: Exception) {
            errorMessage(collectionName, e)
        }
    }

    /**
     * Get the search function for use with dynamic tools.
     */
    fun asSearchFunction(): CollectionSearchFunction = ::search

    private suspend fun executeSearch(
        collectionName: String,
        queryVector: List<Float>
    ): List<Points.ScoredPoint> {
        val searchRequest = SearchPoints.newBuilder()
            .setCollectionName(collectionName)
            .addAllVector(queryVector)
            .setLimit(config.topK.toLong())
            .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
            .build()

        return qdrantClient.searchAsync(searchRequest).await()
    }

    private fun filterByScore(results: List<Points.ScoredPoint>): List<Points.ScoredPoint> {
        val topScore = results.firstOrNull()?.score ?: 0f

        if (topScore < config.searchConfig.minScore) {
            return emptyList()
        }

        val dynamicThreshold = maxOf(
            config.searchConfig.minScore,
            topScore * config.searchConfig.relativeScoreThreshold
        )

        return results.filter { it.score >= dynamicThreshold }
    }

    private fun formatResults(results: List<Points.ScoredPoint>): String {
        return results.joinToString("\n\n") { result ->
            val pageContent = result.payload?.get("page_content")?.stringValue ?: "No content"
            val metadata = result.payload?.get("metadata")?.structValue
            val pageUrl = metadata?.fieldsMap?.get("page_url")?.stringValue
            val page = metadata?.fieldsMap?.get("page")?.integerValue?.toInt()
            val score = result.score

            buildString {
                if (pageUrl != null) append("Source: $pageUrl")
                if (page != null) append(" | Page: $page")
                append("\nContent: $pageContent")
                append("\n(Score: ${String.format("%.3f", score)})")
            }
        }
    }

    private fun noResultsMessage(collectionName: String): String =
        "No relevant documents found in $collectionName."

    private fun errorMessage(collectionName: String, e: Exception): String =
        "Error searching in $collectionName: ${e.message}"

    companion object {
        /**
         * Create a search service from a RAG config.
         */
        fun create(config: RAGConfig): VectorSearchService {
            return VectorSearchService(config)
        }
    }
}
