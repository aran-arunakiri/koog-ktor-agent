package tech.abstracty.agent.rag

import ai.koog.embeddings.local.LLMEmbedder
import io.qdrant.client.QdrantClient
import io.qdrant.client.grpc.JsonWithInt
import io.qdrant.client.grpc.Points
import io.qdrant.client.grpc.Points.SearchPoints
import io.qdrant.client.grpc.Points.WithPayloadSelector
import kotlinx.coroutines.guava.await
import org.slf4j.LoggerFactory

typealias CollectionSearchFunction = suspend (query: String, collection: String) -> String

data class SearchHit(
    val id: String?,
    val url: String?,
    val page: Int?,
    val content: String,
    val score: Float
)

class CollectionSearchService(
    private val config: RagConfig,
    private val clientFactory: RagClientFactory,
) {

    companion object {
        private val logger = LoggerFactory.getLogger(CollectionSearchService::class.java)
    }

    private val qdrantClient: QdrantClient by lazy {
        clientFactory.createQdrantClient()
    }

    private val embedder: LLMEmbedder by lazy {
        clientFactory.createEmbedder()
    }

    suspend fun searchInCollection(
        query: String,
        collectionName: String
    ): String {
        return searchInCollection(query, collectionName, formatter = ::formatSearchHits)
    }

    suspend fun searchInCollection(
        query: String,
        collectionName: String,
        formatter: (List<SearchHit>) -> String,
        noResultsMessage: String = "No relevant documents found in $collectionName.",
        errorMessage: String = "An error occurred while searching $collectionName."
    ): String {
        return try {
            val hits = searchHits(query, collectionName)
            if (hits.isEmpty()) {
                return noResultsMessage
            }
            formatter(hits)
        } catch (e: Exception) {
            logger.error("Search failed in collection: $collectionName", e)
            errorMessage
        }
    }

    suspend fun searchHits(
        query: String,
        collectionName: String
    ): List<SearchHit> {
        val queryVector = generateEmbedding(query)
        val searchResults = executeVectorSearch(collectionName, queryVector)
        val filteredResults = filterResultsByScore(searchResults)
        return filteredResults.mapIndexed { index, result ->
            toSearchHit(result, index)
        }
    }

    private suspend fun generateEmbedding(query: String): List<Float> {
        val embedding = embedder.embed(query)
        return embedding.values.map { it.toFloat() }
    }

    private suspend fun executeVectorSearch(
        collectionName: String,
        queryVector: List<Float>
    ): List<Points.ScoredPoint> {
        val searchRequest = SearchPoints.newBuilder()
            .setCollectionName(collectionName)
            .addAllVector(queryVector)
            .setLimit(config.topK.toLong())
            .setWithPayload(
                WithPayloadSelector.newBuilder().setEnable(true).build()
            )
            .build()

        return qdrantClient.searchAsync(searchRequest).await()
    }

    private fun filterResultsByScore(
        searchResults: List<Points.ScoredPoint>
    ): List<Points.ScoredPoint> {
        val topScore = searchResults.firstOrNull()?.score ?: 0f

        if (topScore < config.searchConfig.minScore) {
            return emptyList()
        }

        val dynamicThreshold = maxOf(
            config.searchConfig.minScore,
            topScore * config.searchConfig.relativeScoreThreshold
        )

        return searchResults.filter { it.score >= dynamicThreshold }
    }

    companion object {
        fun formatSearchHits(hits: List<SearchHit>): String {
            val formattedResults = hits.map { hit ->
                buildString {
                    if (hit.url != null) {
                        append("Source: ${hit.url}")
                    } else {
                        append("Source: Unknown")
                    }
                    if (hit.page != null) append(" • Page: ${hit.page}")
                    append("\nContent: ${hit.content}")
                    append("\n(Score: ${String.format("%.3f", hit.score)})")
                }
            }
            return formattedResults.joinToString("\n\n")
        }
    }

    private fun toSearchHit(result: Points.ScoredPoint, index: Int): SearchHit {
        val payload = result.payloadMap
        val content = payload["page_content"]?.stringValue
            ?: payload["text"]?.stringValue
            ?: "No content"
        val metadata = payload["metadata"]?.structValue
        val url = metadata?.fieldsMap?.get("page_url")?.stringValue
            ?: payload["url"]?.stringValue
        val page = metadata?.fieldsMap?.get("page")?.integerValue?.toInt()
        val id = payload["_id"]?.stringValue ?: url ?: "source_$index"
        return SearchHit(
            id = id,
            url = url,
            page = page,
            content = content,
            score = result.score
        )
    }
}
