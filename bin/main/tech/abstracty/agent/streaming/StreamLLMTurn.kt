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
                // Don't append to prompt here - append all results at the end
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

    // Build result list - either tool calls or assistant message
    val results: List<Message.Response> = toolCalls.ifEmpty {
        listOf(
            Message.Assistant(
                content = full.toString(),
                metaInfo = lastEnd?.metaInfo ?: ResponseMetaInfo.Empty,
                finishReason = lastEnd?.finishReason
            )
        )
    }

    // Append ALL results to prompt at the end (following framework pattern)
    appendPrompt { messages(results) }

    return results
}

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
