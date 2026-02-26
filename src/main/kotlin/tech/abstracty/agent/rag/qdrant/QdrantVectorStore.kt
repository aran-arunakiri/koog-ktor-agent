package tech.abstracty.agent.rag.qdrant

import ai.koog.embeddings.local.LLMEmbedder
import io.qdrant.client.PointIdFactory
import io.qdrant.client.QdrantClient
import io.qdrant.client.ValueFactory
import io.qdrant.client.VectorsFactory
import io.qdrant.client.grpc.Collections
import io.qdrant.client.grpc.JsonWithInt
import io.qdrant.client.grpc.Points
import kotlinx.coroutines.guava.await
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID

data class QdrantCollectionConfig(
    val collectionName: String,
    val vectorSize: Int,
    val topK: Int = 5,
    val minScore: Float = 0.2f,
    val distance: Collections.Distance = Collections.Distance.Cosine
)

class QdrantVectorStore(
    private val config: QdrantCollectionConfig,
    private val qdrantClient: QdrantClient,
    private val embedder: LLMEmbedder,
) {

    companion object {
        private val log = LoggerFactory.getLogger(QdrantVectorStore::class.java)
    }

    data class Document(
        val id: String? = null,
        val text: String,
        val payload: Map<String, String> = emptyMap()
    )

    data class SearchResult(
        val id: String,
        val score: Float,
        val payload: Map<String, String>
    )

    suspend fun ensureCollection() {
        try {
            qdrantClient.createCollectionAsync(
                config.collectionName,
                Collections.VectorParams.newBuilder()
                    .setDistance(config.distance)
                    .setSize(config.vectorSize.toLong())
                    .build()
            ).await()
        } catch (e: Exception) {
            log.debug("createCollectionAsync skipped: ${e.message}")
        }
    }

    suspend fun upsertOne(doc: Document): String {
        val vec = embed(doc.text)
        val id = doc.id ?: UUID.randomUUID().toString()

        val point = Points.PointStruct.newBuilder()
            .setId(pointIdFromString(id))
            .setVectors(VectorsFactory.vectors(vec))
            .apply {
                putPayload("text", ValueFactory.value(doc.text))
                putPayload("_id", ValueFactory.value(id))
                doc.payload.forEach { (k, v) ->
                    putPayload(k, ValueFactory.value(v))
                }
            }
            .build()

        qdrantClient.upsertAsync(config.collectionName, listOf(point)).await()
        return id
    }

    suspend fun upsertBatch(docs: List<Document>) {
        if (docs.isEmpty()) return

        val points = docs.map { doc ->
            val vec = embed(doc.text)
            val id = doc.id ?: UUID.randomUUID().toString()

            Points.PointStruct.newBuilder()
                .setId(pointIdFromString(id))
                .setVectors(VectorsFactory.vectors(vec))
                .apply {
                    putPayload("text", ValueFactory.value(doc.text))
                    putPayload("_id", ValueFactory.value(id))
                    doc.payload.forEach { (k, v) ->
                        putPayload(k, ValueFactory.value(v))
                    }
                }
                .build()
        }

        qdrantClient.upsertAsync(config.collectionName, points).await()
    }

    suspend fun search(
        query: String,
        limitOverride: Int? = null,
        minScoreOverride: Float? = null,
        timeout: Duration = Duration.ofSeconds(5)
    ): List<SearchResult> {
        val queryVec = embed(query)

        val request = Points.SearchPoints.newBuilder()
            .setCollectionName(config.collectionName)
            .addAllVector(queryVec)
            .setLimit((limitOverride ?: config.topK).toLong())
            .setWithPayload(
                Points.WithPayloadSelector.newBuilder().setEnable(true).build()
            )
            .build()

        val rawResults = qdrantClient
            .searchAsync(request, timeout)
            .await()

        val filtered = filterByScore(
            results = rawResults,
            minScore = minScoreOverride ?: config.minScore
        )

        return filtered.map { sp ->
            val payloadMap = sp.payloadMap
            val id = payloadMap["_id"]?.stringValue ?: ""

            SearchResult(
                id = id,
                score = sp.score,
                payload = valueMapToStringMap(payloadMap)
            )
        }
    }

    private suspend fun embed(text: String): List<Float> {
        val embedding = embedder.embed(text)
        return embedding.values.map { it.toFloat() }
    }

    private fun filterByScore(
        results: List<Points.ScoredPoint>,
        minScore: Float
    ): List<Points.ScoredPoint> {
        return results.filter { it.score >= minScore }
    }

    private fun pointIdFromString(id: String): Points.PointId {
        val numeric = id.toLongOrNull()
        return if (numeric != null) {
            PointIdFactory.id(numeric)
        } else {
            val uuid = UUID.nameUUIDFromBytes(id.toByteArray())
            PointIdFactory.id(uuid)
        }
    }

    private fun valueMapToStringMap(src: Map<String, JsonWithInt.Value>): Map<String, String> {
        return src.mapValues { (_, v) -> v.stringValue }
    }
}
