package tech.abstracty.agent.dev

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTool
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.dsl.extension.onToolCall
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.core.environment.result
import ai.koog.agents.core.tools.Tool
import ai.koog.prompt.message.Message
import kotlinx.coroutines.runBlocking
import tech.abstracty.agent.agent.StreamingAgentBuilder
import tech.abstracty.agent.rag.CollectionSearchService
import tech.abstracty.agent.rag.DefaultRagClientFactory
import tech.abstracty.agent.rag.RagConfig
import tech.abstracty.agent.rag.tools.CachedToolDescriptionRepository
import tech.abstracty.agent.rag.tools.DynamicSearchTool
import tech.abstracty.agent.rag.tools.FileBasedToolDescriptionRepository
import tech.abstracty.agent.protocol.FinishReason
import tech.abstracty.agent.protocol.StreamBridge
import tech.abstracty.agent.protocol.Usage
import tech.abstracty.agent.streaming.streamLLMTurn

private class ConsoleBridge : StreamBridge {
    override suspend fun onTextDelta(delta: String) {
        print(delta)
    }

    override suspend fun onToolCallStart(callId: String, toolName: String, args: String?) {
        println("\n[tool] $toolName ($callId) ${args ?: ""}".trim())
    }

    override suspend fun onToolCallResult(callId: String, result: Any?, isError: Boolean) {
        println("[tool-result] $callId ${result ?: ""}".trim())
    }

    override suspend fun onFinish(reason: FinishReason, usage: Usage?) {
        println("\n[done] $reason")
    }

    override suspend fun onError(message: String) {
        println("\n[error] $message")
    }
}

private fun createCliStrategy(bridge: StreamBridge): AIAgentGraphStrategy<String, String> = strategy("cli") {
    val nodeCallLLM by nodeLLMRequest("sendInput")
    val nodeExecuteTool by nodeExecuteTool("nodeExecuteTool")

    val nodeSendToolResult by node<ReceivedToolResult, Message.Response>("nodeSendToolResult") { toolResult ->
        llm.writeSession {
            streamLLMTurn(
                onText = { delta -> bridge.onTextDelta(delta) },
                onToolCall = { },
                onEnd = { },
            ) {
                appendPrompt {
                    tool { result(toolResult) }
                }
            }
        }
    }

    val giveFeedbackToCallTools by node<String, Message.Response> { _ ->
        llm.writeSession {
            appendPrompt {
                user(
                    "Don't chat with plain text! Call one of the available tools, instead: ${tools.joinToString(", ") {
                        it.name
                    }}"
                )
            }
            requestLLM()
        }
    }

    edge(nodeStart forwardTo nodeCallLLM)
    edge(nodeCallLLM forwardTo nodeExecuteTool onToolCall { true })
    edge(nodeCallLLM forwardTo giveFeedbackToCallTools onAssistantMessage { true })

    edge(giveFeedbackToCallTools forwardTo giveFeedbackToCallTools onAssistantMessage { true })
    edge(giveFeedbackToCallTools forwardTo nodeExecuteTool onToolCall { true })

    edge(nodeExecuteTool forwardTo nodeSendToolResult)
    edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCall { true })
    edge(nodeSendToolResult forwardTo nodeFinish onAssistantMessage { true })
    edge(nodeSendToolResult forwardTo nodeFinish onToolCall { tc -> tc.tool == "__exit__" }
        transformed { "Chat finished" }
    )
}

private fun createPlainChatStrategy(): AIAgentGraphStrategy<String, String> = strategy("plain") {
    val nodeCallLLM by nodeLLMRequest("sendInput")
    edge(nodeStart forwardTo nodeCallLLM)
    edge(nodeCallLLM forwardTo nodeFinish onAssistantMessage { true })
}

private fun createPlainStreamingStrategy(bridge: StreamBridge): AIAgentGraphStrategy<String, String> = strategy("plain-stream") {
    val nodeStream by node<String, Message.Response>("streamInput") { input ->
        llm.writeSession {
            streamLLMTurn(
                onText = { delta -> bridge.onTextDelta(delta) },
                onToolCall = { },
                onEnd = { },
            ) {
                appendPrompt { user(input) }
            }
        }
    }

    edge(nodeStart forwardTo nodeStream)
    edge(nodeStream forwardTo nodeFinish onAssistantMessage { true })
}

private fun loadRagTools(apiKey: String): List<Tool<*, *>> {
    val basePath = System.getenv("TOOL_DESCRIPTIONS_PATH") ?: return emptyList()
    val tenantId = System.getenv("RAG_TENANT_ID") ?: "default"
    val qdrantHost = System.getenv("QDRANT_HOST") ?: "localhost"
    val qdrantPort = System.getenv("QDRANT_GRPC_PORT")?.toIntOrNull() ?: 6334
    val embeddingModel = System.getenv("EMBEDDING_MODEL") ?: "text-embedding-3-small"
    val topK = System.getenv("RAG_TOP_K")?.toIntOrNull() ?: 5

    val ragConfig = RagConfig(
        tenantId = tenantId,
        qdrantHost = qdrantHost,
        qdrantGrpcPort = qdrantPort,
        openAIApiKey = apiKey,
        embeddingModel = embeddingModel,
        topK = topK
    )
    val clientFactory = DefaultRagClientFactory(ragConfig)
    val searchService = CollectionSearchService(ragConfig, clientFactory)

    val baseRepo = FileBasedToolDescriptionRepository(basePath)
    val cachedRepo = CachedToolDescriptionRepository(baseRepo)
    val descriptions = cachedRepo.getDescriptions(tenantId)

    return descriptions.map { description ->
        DynamicSearchTool(
            toolDescription = description,
            searchFunction = searchService::searchInCollection
        )
    }
}

fun main() = runBlocking {
    val apiKey = System.getenv("OPENAI_API_KEY") ?: error("OPENAI_API_KEY is required")
    val systemPrompt = System.getenv("SYSTEM_PROMPT") ?: "You are a helpful assistant."

    val bridge = ConsoleBridge()
    val ragTools = loadRagTools(apiKey)
    if (ragTools.isEmpty()) {
        println("No RAG tools loaded. Set TOOL_DESCRIPTIONS_PATH and QDRANT_HOST/QDRANT_GRPC_PORT to enable.")
    }
    val strategyMode = System.getenv("CLI_STRATEGY")
        ?: if (ragTools.isEmpty()) "plain-stream" else "tools"
    val agent = StreamingAgentBuilder.create(bridge) {
        this.apiKey = apiKey
        this.systemPrompt = systemPrompt
        strategy = when (strategyMode.lowercase()) {
            "plain" -> createPlainChatStrategy()
            "plain-stream" -> createPlainStreamingStrategy(bridge)
            else -> createCliStrategy(bridge)
        }
        tools {
            ragTools.forEach { +it }
        }
    }

    println("CLI ready. Type a message, or 'exit' to quit.")
    while (true) {
        print("> ")
        val input = readLine()?.trim() ?: break
        if (input.equals("exit", ignoreCase = true)) break
        agent.run(input)
    }
}
