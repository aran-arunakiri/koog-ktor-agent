package tech.abstracty.agent.agent

import ai.koog.agents.core.agent.AIAgent
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
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import tech.abstracty.agent.protocol.StreamBridge
import tech.abstracty.agent.streaming.streamLLMTurn

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

    private val toolList = mutableListOf<Tool<*, *>>()

    /**
     * Add tools to the agent.
     */
    fun tools(block: ToolsBuilder.() -> Unit) {
        ToolsBuilder(toolList).apply(block)
    }

    fun build(): AIAgent<String, String> {
        require(apiKey.isNotBlank()) { "API key is required" }

        val tools = toolList
        val toolRegistry = ToolRegistry { tools.forEach { tool(it) } }

        return AIAgent(
            promptExecutor = simpleOpenAIExecutor(apiKey),
            systemPrompt = systemPrompt,
            llmModel = model,
            strategy = createStreamingStrategy(),
            toolRegistry = toolRegistry,
            installFeatures = {
                install(EventHandler) {
                    onToolCallStarting { ctx ->
                        bridge.onToolCallStart(
                            callId = "call_${ctx.tool.name}_${System.currentTimeMillis()}",
                            toolName = ctx.tool.name
                        )
                    }
                    onToolCallCompleted { ctx ->
                        bridge.onToolCallResult(
                            callId = "call_${ctx.tool.name}",
                            result = ctx.result
                        )
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
            }
        )
    }

    private fun createStreamingStrategy(): AIAgentGraphStrategy<String, String> = strategy("chat") {
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
