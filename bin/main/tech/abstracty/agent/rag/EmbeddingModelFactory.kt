package tech.abstracty.agent.rag

import ai.koog.embeddings.local.LLMEmbedder
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.llm.LLModel

object EmbeddingModelFactory {
    fun createEmbedder(modelName: String, client: OpenAILLMClient): LLMEmbedder {
        val model = getEmbeddingModel(modelName)
        return LLMEmbedder(client, model)
    }

    fun getEmbeddingModel(modelName: String): LLModel {
        return when (modelName) {
            "text-embedding-ada-002" -> OpenAIModels.Embeddings.TextEmbeddingAda002
            "text-embedding-3-small" -> OpenAIModels.Embeddings.TextEmbedding3Small
            "text-embedding-3-large" -> OpenAIModels.Embeddings.TextEmbedding3Large
            else -> OpenAIModels.Embeddings.TextEmbedding3Small
        }
    }

    fun getVectorSize(modelName: String): Int {
        return when (modelName) {
            "text-embedding-ada-002", "text-embedding-3-small" -> 1536
            "text-embedding-3-large" -> 3072
            else -> 1536
        }
    }
}
