package tech.abstracty.agent.dev

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteMultipleToolsAndSendResults
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStreamingAndSendResults
import ai.koog.agents.core.dsl.extension.onMultipleAssistantMessages
import ai.koog.agents.core.dsl.extension.onMultipleToolCalls
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.streaming.StreamFrame
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

/**
 * Tools-mode strategy.
 *
 * Graph: nodeStart → setupUserPrompt → streamLLM ⇄ executeTools → nodeFinish
 *
 * On koog 0.8 the streaming + tool-dispatch loop is expressed by composing
 * `nodeLLMRequestStreamingAndSendResults` (streams the LLM turn and appends
 * the full response set back to the session) with
 * `nodeExecuteMultipleToolsAndSendResults` (runs every requested tool through
 * the `ToolRegistry`, appends results, then re-asks the LLM). Tool-call and
 * text-delta events reach the [StreamBridge] via the `EventHandler` feature
 * — `StreamingAgentBuilder` already wires the tool-call triplet, and
 * [installStreamingTextBridge] wires the text deltas.
 *
 * The pre-0.8 "nudge the LLM to call a tool when it tries plain text" loop
 * is preserved by `nudgeForTools`: if the LLM streams only assistant text
 * (no tool calls), we append a brief user-side reminder of available tool
 * names and re-stream — same observable CLI behavior as before, just routed
 * through edges instead of a manual `requestLLM()` call.
 */
private fun createCliStrategy(toolNames: List<String>): AIAgentGraphStrategy<String, String> = strategy("cli") {
    val setupUserPrompt by node<String, String>("setupUserPrompt") { input ->
        llm.writeSession {
            appendPrompt { user(input) }
        }
        input
    }

    val nudgeForTools by node<List<ai.koog.prompt.message.Message.Response>, String>("nudgeForTools") { _ ->
        llm.writeSession {
            appendPrompt {
                user(
                    "Don't chat with plain text! Call one of the available tools, instead: " +
                        toolNames.joinToString(", ")
                )
            }
        }
        ""
    }

    val streamLLM by nodeLLMRequestStreamingAndSendResults<String>("streamLLM")
    val executeTools by nodeExecuteMultipleToolsAndSendResults(
        name = "executeTools",
        parallelTools = false,
    )

    edge(nodeStart forwardTo setupUserPrompt)
    edge(setupUserPrompt forwardTo streamLLM)

    // LLM produced tool calls → run them, then loop in case of multi-turn tool use.
    edge(streamLLM forwardTo executeTools onMultipleToolCalls { true })
    edge(executeTools forwardTo executeTools onMultipleToolCalls { true })

    // LLM produced plain text (no tools) → nudge it to use a tool and re-stream.
    edge(streamLLM forwardTo nudgeForTools onMultipleAssistantMessages { true })
    edge(nudgeForTools forwardTo streamLLM)

    // Tools-then-assistant-text → finish (this is the natural happy path after
    // a tool round-trip: the LLM weaves the result into a final assistant turn).
    edge(
        executeTools forwardTo nodeFinish
            onMultipleAssistantMessages { true }
            transformed { msgs -> msgs.lastOrNull()?.content ?: "" }
    )
}

/**
 * Non-tools, single-shot strategy: nodeStart → streamLLM → nodeFinish.
 * Used when no RAG tools are configured and CLI_STRATEGY=plain.
 *
 * `nodeLLMRequestStreamingAndSendResults` still streams tokens (the
 * EventHandler bridge installed by [installStreamingTextBridge] writes them
 * to stdout); since there are no tools the tool-call edge is never taken.
 */
private fun createPlainChatStrategy(): AIAgentGraphStrategy<String, String> = strategy("plain") {
    val setupUserPrompt by node<String, String>("setupUserPrompt") { input ->
        llm.writeSession {
            appendPrompt { user(input) }
        }
        input
    }

    val streamLLM by nodeLLMRequestStreamingAndSendResults<String>("streamLLM")

    edge(nodeStart forwardTo setupUserPrompt)
    edge(setupUserPrompt forwardTo streamLLM)
    edge(
        streamLLM forwardTo nodeFinish
            onMultipleAssistantMessages { true }
            transformed { msgs -> msgs.lastOrNull()?.content ?: "" }
    )
}

/**
 * Plain streaming strategy. Functionally identical to [createPlainChatStrategy]
 * on koog 0.8 — both stream via `nodeLLMRequestStreamingAndSendResults`. The
 * old DSL distinguished "request" (non-streaming) from "streamLLMTurn"
 * (streaming) at the node level; the 0.8 builtin always streams. Kept as a
 * separate factory so CLI_STRATEGY=plain-stream still resolves.
 */
private fun createPlainStreamingStrategy(): AIAgentGraphStrategy<String, String> = createPlainChatStrategy()

/**
 * Install an EventHandler that forwards LLM streaming text deltas to the bridge.
 *
 * `StreamingAgentBuilder` already installs an EventHandler that handles
 * tool-call boundaries + agent/node failures, but it does NOT subscribe to
 * `onLLMStreamingFrameReceived`. This second EventHandler stacks on top via
 * the builder's `extraFeatures` hook so the CLI keeps echoing tokens as they
 * arrive — same UX as the pre-0.8 manual `streamLLMTurn(onText = ...)`.
 */
private fun installStreamingTextBridge(bridge: StreamBridge): ai.koog.agents.core.agent.GraphAIAgent.FeatureContext.() -> Unit = {
    install(EventHandler) {
        onLLMStreamingFrameReceived { ctx ->
            val frame = ctx.streamFrame
            if (frame is StreamFrame.TextDelta && frame.text.isNotEmpty()) {
                bridge.onTextDelta(frame.text)
            }
        }
    }
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
    val debug = System.getenv("CLI_DEBUG")?.lowercase() == "true"

    val bridge = ConsoleBridge()
    val ragTools = loadRagTools(apiKey)
    if (ragTools.isEmpty()) {
        println("No RAG tools loaded. Set TOOL_DESCRIPTIONS_PATH and QDRANT_HOST/QDRANT_GRPC_PORT to enable.")
    }
    val strategyMode = System.getenv("CLI_STRATEGY")
        ?: if (ragTools.isEmpty()) "plain-stream" else "tools"
    val toolNames = ragTools.map { it.name }
    val agent = StreamingAgentBuilder.create(bridge) {
        this.apiKey = apiKey
        this.systemPrompt = systemPrompt
        strategy = when (strategyMode.lowercase()) {
            "plain" -> createPlainChatStrategy()
            "plain-stream" -> createPlainStreamingStrategy()
            else -> createCliStrategy(toolNames)
        }
        tools {
            ragTools.forEach { +it }
        }
        // Layer a second EventHandler on top of the builder's built-in one so
        // we still stream token deltas to stdout (the builder only wires
        // tool-call + failure events).
        extraFeatures = installStreamingTextBridge(bridge)
    }

    println("CLI ready. Type a message, or 'exit' to quit.")
    while (true) {
        print("> ")
        val input = readLine()?.trim() ?: break
        if (input.equals("exit", ignoreCase = true)) break
        if (debug) println("[debug] sending: $input")
        try {
            val result = agent.run(input)
            if (debug) println("\n[debug] result: $result")
            bridge.onFinish(FinishReason.STOP, Usage())
        } catch (e: Exception) {
            bridge.onError(e.message ?: "LLM call failed")
        }
    }
}
