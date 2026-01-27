package tech.abstracty.agent.protocol.openai

import kotlinx.serialization.json.Json
import tech.abstracty.agent.protocol.FinishReason
import tech.abstracty.agent.protocol.StreamBridge
import tech.abstracty.agent.protocol.Usage
import java.io.Writer

/**
 * StreamBridge implementation for OpenAI-compatible SSE protocol.
 *
 * Used for LibreChat and other OpenAI-compatible frontends.
 *
 * Format: `data: {json}\n\n`
 * Ends with: `data: [DONE]\n\n`
 *
 * Example usage:
 * ```kotlin
 * call.respondTextWriter(ContentType.Text.EventStream) {
 *     val bridge = OpenAISseBridge(this, model = "gpt-4")
 *     // Use bridge with your agent
 *     bridge.finish()
 * }
 * ```
 */
class OpenAISseBridge(
    private val writer: Writer,
    private val model: String,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
) : StreamBridge {

    private val id = "chatcmpl-${System.currentTimeMillis()}"
    private val created = System.currentTimeMillis() / 1000

    private var started = false
    private var sawToolCall = false
    private var finished = false

    override suspend fun onTextDelta(delta: String) {
        if (delta.isEmpty()) return

        val deltaObj = if (!started) {
            started = true
            Delta(role = "assistant", content = delta)
        } else {
            Delta(content = delta)
        }
        sawToolCall = false
        sendChunk(deltaObj)
    }

    override suspend fun onToolCallStart(callId: String, toolName: String, args: String?) {
        sawToolCall = true

        val delta = Delta(
            role = if (!started) { started = true; "assistant" } else null,
            tool_calls = listOf(
                DeltaToolCall(
                    index = 0,
                    id = callId,
                    type = "function",
                    function = DeltaFunctionCall(
                        name = toolName,
                        arguments = args ?: ""
                    )
                )
            )
        )
        sendChunk(delta, finishReason = "tool_calls")
    }

    override suspend fun onToolCallArgsDelta(callId: String, argsDelta: String) {
        val delta = Delta(
            tool_calls = listOf(
                DeltaToolCall(
                    index = 0,
                    function = DeltaFunctionCall(arguments = argsDelta)
                )
            )
        )
        sendChunk(delta)
    }

    override suspend fun onToolCallResult(callId: String, result: Any?, isError: Boolean) {
        // OpenAI SSE doesn't stream tool results back to the client the same way
        // Tool results are typically handled in subsequent turns
        // This is a no-op for OpenAI protocol
    }

    override suspend fun onFinish(reason: FinishReason, usage: Usage?) {
        if (!sawToolCall) {
            sendChunk(Delta(), finishReason = reason.toOpenAI())
        }
        sendDone()
    }

    override suspend fun onError(message: String) {
        writer.write("data: {\"error\":\"$message\"}\n\n")
        writer.flush()
        sendDone()
    }

    /**
     * Call this to send the final [DONE] message.
     * Usually called automatically by onFinish.
     */
    fun sendDone() {
        if (finished) return
        finished = true
        writer.write("data: [DONE]\n\n")
        writer.flush()
    }

    private fun sendChunk(delta: Delta, finishReason: String? = null) {
        val chunk = StreamingChunk(
            id = id,
            `object` = "chat.completion.chunk",
            created = created,
            model = model,
            choices = listOf(
                StreamingChoice(
                    index = 0,
                    delta = delta,
                    finish_reason = finishReason
                )
            )
        )

        val payload = json.encodeToString(StreamingChunk.serializer(), chunk)
        writer.write("data: $payload\n\n")
        writer.flush()
    }

    private fun FinishReason.toOpenAI(): String = when (this) {
        FinishReason.STOP -> "stop"
        FinishReason.LENGTH -> "length"
        FinishReason.TOOL_CALLS -> "tool_calls"
        FinishReason.CONTENT_FILTER -> "content_filter"
        FinishReason.OTHER -> "stop"
    }
}
