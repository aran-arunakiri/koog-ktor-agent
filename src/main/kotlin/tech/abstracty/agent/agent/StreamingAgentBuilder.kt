package tech.abstracty.agent.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import ai.koog.prompt.llm.LLModel
import tech.abstracty.agent.protocol.StreamBridge

/**
 * DSL builder for creating streaming AI agents.
 *
 * Example usage:
 * ```kotlin
 * val agent = StreamingAgentBuilder.create(bridge) {
 *     apiKey = config.openAIApiKey
 *     systemPrompt = "You are a helpful assistant"
 *     model = OpenAIModels.Chat.GPT4_1
 *     tools {
 *         +mySearchTool
 *         +myRagTool
 *     }
 * }
 *
 * agent.run(userMessage)
 * bridge.onFinish(FinishReason.STOP, Usage(10, 50))
 * ```
 */
class StreamingAgentBuilder(
    private val bridge: StreamBridge
) {
    var apiKey: String = ""
    var systemPrompt: String = ""
    var model: LLModel = OpenAIModels.Chat.GPT4_1
    var strategy: AIAgentGraphStrategy<String, String>? = null
    var extraFeatures: (GraphAIAgent.FeatureContext.() -> Unit)? = null

    private val toolList = mutableListOf<Tool<*, *>>()
    private val toolCallStack = ArrayDeque<String>()

    /**
     * Add tools to the agent.
     */
    fun tools(block: ToolsBuilder.() -> Unit) {
        ToolsBuilder(toolList).apply(block)
    }

    fun build(): AIAgent<String, String> {
        require(apiKey.isNotBlank()) { "API key is required" }
        val agentStrategy = requireNotNull(strategy) { "Agent strategy is required" }

        val tools = toolList
        val toolRegistry = ToolRegistry { tools.forEach { tool(it) } }

        return AIAgent(
            promptExecutor = simpleOpenAIExecutor(apiKey),
            systemPrompt = systemPrompt,
            llmModel = model,
            strategy = agentStrategy,
            toolRegistry = toolRegistry,
            installFeatures = {
                install(EventHandler) {
                    onToolCallStarting { ctx ->
                        val callId = "call_${ctx.tool.name}_${System.currentTimeMillis()}"
                        toolCallStack.addLast(callId)
                        bridge.onToolCallStart(callId, ctx.tool.name)
                    }
                    onToolCallCompleted { ctx ->
                        val callId = if (toolCallStack.isNotEmpty()) {
                            toolCallStack.removeLast()
                        } else {
                            "call_${ctx.tool.name}"
                        }
                        bridge.onToolCallResult(callId, ctx.result)
                    }
                    onAgentExecutionFailed {
                        bridge.onError(it.throwable.message ?: "Agent execution failed")
                    }
                    onToolCallFailed {
                        bridge.onError(it.throwable.message ?: "Tool call failed")
                    }
                    onLLMStreamingFailed {
                        bridge.onError(it.error.message ?: "LLM streaming failed")
                    }
                    onNodeExecutionFailed {
                        bridge.onError(it.throwable.message ?: "Node execution failed")
                    }
                }
                extraFeatures?.invoke(this)
            }
        )
    }

    companion object {
        /**
         * Create a streaming agent with DSL syntax.
         */
        fun create(bridge: StreamBridge, block: StreamingAgentBuilder.() -> Unit): AIAgent<String, String> {
            return StreamingAgentBuilder(bridge).apply(block).build()
        }
    }
}

/**
 * Builder for adding tools to an agent.
 */
class ToolsBuilder(private val tools: MutableList<Tool<*, *>>) {
    /**
     * Add a tool using the unary plus operator.
     */
    operator fun Tool<*, *>.unaryPlus() {
        tools.add(this)
    }

    /**
     * Add multiple tools.
     */
    fun addAll(vararg newTools: Tool<*, *>) {
        tools.addAll(newTools)
    }

    /**
     * Add a list of tools.
     */
    fun addAll(newTools: List<Tool<*, *>>) {
        tools.addAll(newTools)
    }
}
