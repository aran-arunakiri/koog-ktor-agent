package tech.abstracty.agent.rag

import ai.koog.embeddings.local.LLMEmbedder
import io.qdrant.client.QdrantClient
import io.qdrant.client.grpc.Points
import io.qdrant.client.grpc.Points.SearchPoints
import io.qdrant.client.grpc.Points.WithPayloadSelector
import kotlinx.coroutines.guava.await
import org.slf4j.LoggerFactory

typealias CollectionSearchFunction = suspend (query: String, collection: String) -> String

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
        return try {
            val queryVector = generateEmbedding(query)
            val searchResults = executeVectorSearch(collectionName, queryVector)
            if (searchResults.isEmpty()) {
                return "Geen relevante documenten gevonden in $collectionName."
            }
            val filteredResults = filterResultsByScore(searchResults)
            if (filteredResults.isEmpty()) {
                return "Geen relevante documenten gevonden in $collectionName."
            }
            formatSearchResults(filteredResults)
        } catch (e: Exception) {
            logger.error("Search failed in collection: $collectionName", e)
            "Er is een fout opgetreden bij het zoeken in $collectionName."
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

    private fun formatSearchResults(results: List<Points.ScoredPoint>): String {
        val formattedResults = results.map { result ->
            val payload = result.payloadMap
            val pageContent = payload["page_content"]?.stringValue
                ?: payload["text"]?.stringValue
                ?: "Geen inhoud"
            val metadata = payload["metadata"]?.structValue
            val pageURL = metadata?.fieldsMap?.get("page_url")?.stringValue
                ?: payload["url"]?.stringValue
            val page = metadata?.fieldsMap?.get("page")?.integerValue?.toInt()
            val score = result.score

            buildString {
                if (pageURL != null) {
                    append("Bron: $pageURL")
                } else {
                    append("Bron: Onbekend")
                }
                if (page != null) append(" • Pagina: $page")
                append("\nInhoud: $pageContent")
                append("\n(Score: ${String.format("%.3f", score)})")
            }
        }

        return formattedResults.joinToString("\n\n")
    }
}
