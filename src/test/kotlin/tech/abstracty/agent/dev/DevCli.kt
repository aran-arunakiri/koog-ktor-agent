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
import ai.koog.prompt.message.Message
import kotlinx.coroutines.runBlocking
import tech.abstracty.agent.agent.StreamingAgentBuilder
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

fun main() = runBlocking {
    val apiKey = System.getenv("OPENAI_API_KEY") ?: error("OPENAI_API_KEY is required")
    val systemPrompt = System.getenv("SYSTEM_PROMPT") ?: "You are a helpful assistant."

    val bridge = ConsoleBridge()
    val agent = StreamingAgentBuilder.create(bridge) {
        this.apiKey = apiKey
        this.systemPrompt = systemPrompt
        strategy = createCliStrategy(bridge)
    }

    println("CLI ready. Type a message, or 'exit' to quit.")
    while (true) {
        print("> ")
        val input = readLine()?.trim() ?: break
        if (input.equals("exit", ignoreCase = true)) break
        agent.run(input)
    }
}
