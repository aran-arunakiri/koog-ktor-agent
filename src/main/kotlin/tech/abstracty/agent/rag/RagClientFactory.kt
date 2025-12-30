package tech.abstracty.agent.rag

import ai.koog.embeddings.local.LLMEmbedder
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import org.slf4j.LoggerFactory

interface RagClientFactory {
    fun createQdrantClient(): QdrantClient
    fun createEmbedder(): LLMEmbedder
    fun createOpenAIClient(): OpenAILLMClient
}

class DefaultRagClientFactory(
    private val config: RagConfig
) : RagClientFactory {

    companion object {
        private val logger = LoggerFactory.getLogger(DefaultRagClientFactory::class.java)
    }

    private val openAIClient: OpenAILLMClient by lazy {
        logger.debug("Creating OpenAI client for tenant: ${config.tenantId}")
        OpenAILLMClient(config.openAIApiKey)
    }

    private val qdrantClient: QdrantClient by lazy {
        createQdrantClient()
    }

    override fun createQdrantClient(): QdrantClient {
        return if (config.connectionPoolConfig.useConnectionPool) {
            logger.debug("Creating pooled Qdrant client for tenant: ${config.tenantId}")
            QdrantClientPool.getClient(
                config.tenantId,
                config.qdrantHost,
                config.qdrantGrpcPort
            )
        } else {
            logger.debug("Creating standalone Qdrant client for tenant: ${config.tenantId}")
            QdrantClient(
                QdrantGrpcClient.newBuilder(
                    config.qdrantHost,
                    config.qdrantGrpcPort,
                    false
                ).build()
            )
        }
    }

    override fun createEmbedder(): LLMEmbedder {
        logger.debug("Creating embedder with model: ${config.embeddingModel}")
        return EmbeddingModelFactory.createEmbedder(
            config.embeddingModel,
            openAIClient
        )
    }

    override fun createOpenAIClient(): OpenAILLMClient {
        return openAIClient
    }
}
