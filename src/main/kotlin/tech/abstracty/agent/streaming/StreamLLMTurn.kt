package tech.abstracty.agent.streaming

import ai.koog.agents.core.agent.session.AIAgentLLMWriteSession
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import java.util.*

/**
 * Generic streaming helper that processes Koog's StreamFrame events.
 *
 * This is protocol-agnostic - it just exposes callbacks that can be
 * wired to any StreamBridge implementation.
 *
 * Example usage:
 * ```kotlin
 * llm.writeSession {
 *     streamLLMTurn(
 *         onText = { delta -> bridge.onTextDelta(delta) },
 *         onToolCall = { call -> bridge.onToolCallStart(call.id, call.tool, call.content) },
 *         onEnd = { },
 *     ) {
 *         appendPrompt { user(query) }
 *     }
 * }
 * ```
 */
suspend fun AIAgentLLMWriteSession.streamLLMTurn(
    onText: suspend (String) -> Unit,
    onToolCall: suspend (Message.Tool.Call) -> Unit,
    onEnd: suspend (StreamFrame.End?) -> Unit,
    buildInitial: AIAgentLLMWriteSession.() -> Unit,
): List<Message.Response> {
    buildInitial()

    val frames = requestLLMStreaming()
    val full = StringBuilder()
    val toolCalls = mutableListOf<Message.Tool.Call>()
    var lastEnd: StreamFrame.End? = null

    frames.collect { frame ->
        when (frame) {
            is StreamFrame.Append -> if (frame.text.isNotEmpty() && toolCalls.isEmpty()) {
                full.append(frame.text)
                onText(frame.text)
            }

            is StreamFrame.ToolCall -> {
                val safeId = frame.id ?: UUID.randomUUID().toString()
                // Collect tool calls - they'll be appended to prompt at the end using tool DSL
                val call = Message.Tool.Call(
                    id = safeId,
                    tool = frame.name,
                    content = frame.content,
                    metaInfo = ResponseMetaInfo.Empty
                )

                toolCalls.add(call)
                onToolCall(call)
            }

            is StreamFrame.End -> {
                lastEnd = frame
                onEnd(frame)
            }
        }
    }

    // Build result and append to prompt
    return if (toolCalls.isNotEmpty()) {
        // Append tool calls using the tool DSL - this is required for OpenAI format
        // The tool DSL properly creates Message.Tool.Call entries that get converted
        // to OpenAI's assistant message with tool_calls array
        appendPrompt {
            tool { toolCalls.forEach { call(it.id!!, it.tool, it.content) } }
        }
        toolCalls
    } else {
        val assistantMessage = Message.Assistant(
            content = full.toString(),
            metaInfo = lastEnd?.metaInfo ?: ResponseMetaInfo.Empty,
            finishReason = lastEnd?.finishReason
        )
        appendPrompt { messages(listOf(assistantMessage)) }
        listOf(assistantMessage)
    }
}

/**
 * Non-streaming helper that returns List<Message.Response>.
 * Use this when you need multiple responses but don't need to stream to user.
 */
suspend fun AIAgentLLMWriteSession.requestLLMMultiple(
    buildPrompt: AIAgentLLMWriteSession.() -> Unit,
): List<Message.Response> = streamLLMTurn(
    onText = { },
    onToolCall = { },
    onEnd = { },
    buildInitial = buildPrompt
)

/**
 * Simplified streaming helper that just collects text.
 */
suspend fun AIAgentLLMWriteSession.streamText(
    onText: suspend (String) -> Unit,
    buildInitial: AIAgentLLMWriteSession.() -> Unit,
): String {
    buildInitial()

    val frames = requestLLMStreaming()
    val full = StringBuilder()

    frames.collect { frame ->
        when (frame) {
            is StreamFrame.Append -> if (frame.text.isNotEmpty()) {
                full.append(frame.text)
                onText(frame.text)
            }
            else -> {}
        }
    }

    return full.toString()
}
