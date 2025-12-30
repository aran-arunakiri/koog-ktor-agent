package tech.abstracty.agent.rag

import ai.koog.embeddings.local.LLMEmbedder
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.llm.LLModel

/**
 * Service for generating text embeddings using OpenAI models.
 */
class EmbeddingService(
    private val apiKey: String,
    private val model: EmbeddingModel = EmbeddingModel.TEXT_EMBEDDING_3_SMALL,
) {
    private val client: OpenAILLMClient by lazy {
        OpenAILLMClient(apiKey)
    }

    private val embedder: LLMEmbedder by lazy {
        val llmModel = model.toLLModel()
        LLMEmbedder(client, llmModel)
    }

    /**
     * Generate embedding for the given text.
     *
     * @return List of floats representing the embedding vector
     */
    suspend fun embed(text: String): List<Float> {
        val embedding = embedder.embed(text)
        return embedding.values.map { it.toFloat() }
    }

    /**
     * Get the dimensionality of the embedding vectors.
     */
    fun dimensions(): Int = model.dimensions

    companion object {
        /**
         * Create an embedding service from a RAG config.
         */
        fun fromConfig(config: RAGConfig): EmbeddingService {
            return EmbeddingService(
                apiKey = config.openAIApiKey,
                model = config.embeddingModel,
            )
        }
    }
}

/**
 * Convert EmbeddingModel to Koog LLModel.
 */
internal fun EmbeddingModel.toLLModel(): LLModel {
    return when (this) {
        EmbeddingModel.TEXT_EMBEDDING_ADA_002 -> OpenAIModels.Embeddings.TextEmbeddingAda002
        EmbeddingModel.TEXT_EMBEDDING_3_SMALL -> OpenAIModels.Embeddings.TextEmbedding3Small
        EmbeddingModel.TEXT_EMBEDDING_3_LARGE -> OpenAIModels.Embeddings.TextEmbedding3Large
    }
}
