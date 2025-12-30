package tech.abstracty.agent.protocol.assistantui

import kotlinx.serialization.json.*
import tech.abstracty.agent.protocol.FinishReason
import tech.abstracty.agent.protocol.StreamBridge
import tech.abstracty.agent.protocol.Usage

/**
 * StreamBridge implementation for Assistant-UI DataStream protocol.
 *
 * Translates generic agent events into Assistant-UI frames:
 * - Text deltas → frame "0"
 * - Tool calls → frames "b" (begin), "a" (result)
 * - Sources → frame "h"
 * - Finish → frame "d"
 *
 * Example usage:
 * ```kotlin
 * call.respondTextWriter(ContentType.Text.Plain) {
 *     val bridge = AssistantUIBridge(this)
 *     // Use bridge with your agent
 * }
 * ```
 */
class AssistantUIBridge(
    out: Appendable,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : StreamBridge {

    private val writer = LineFramedWriter(out, json)

    private data class PendingSource(
        val id: String,
        val url: String,
        val title: String?,
        val parentId: String,
    )

    private val toolCallStack = ArrayDeque<String>()
    private var toolCounter: Int = 0
    private val pendingSources = mutableListOf<PendingSource>()

    override suspend fun onTextDelta(delta: String) {
        if (delta.isNotEmpty()) {
            writer.text(delta)
        }
    }

    override suspend fun onToolCallStart(callId: String, toolName: String, args: String?) {
        toolCallStack.addLast(callId)
        writer.toolBegin(
            callId = callId,
            toolName = toolName,
            parentId = null,
        )
    }

    override suspend fun onToolCallArgsDelta(callId: String, argsDelta: String) {
        writer.toolArgsDelta(callId, argsDelta)
    }

    override suspend fun onToolCallResult(callId: String, result: Any?, isError: Boolean) {
        val actualCallId = if (toolCallStack.isNotEmpty() && toolCallStack.last() == callId) {
            toolCallStack.removeLast()
        } else {
            callId
        }

        val resultJson = serializeResult(result)

        writer.toolResult(
            callId = actualCallId,
            result = resultJson,
            artifact = null,
            isError = if (isError) true else null,
        )
    }

    override suspend fun onSource(id: String, url: String, title: String?) {
        val parentId = toolCallStack.lastOrNull() ?: "root"
        pendingSources += PendingSource(
            id = id,
            url = url,
            title = title,
            parentId = parentId,
        )
    }

    override suspend fun onFinish(reason: FinishReason, usage: Usage?) {
        flushSources()
        writer.finishMessage(
            finish = reason.toAui(),
            usage = (usage ?: Usage()).toAui()
        )
    }

    override suspend fun onError(message: String) {
        writer.error(message)
    }

    /**
     * Add a source/citation that will be flushed at the end of the turn.
     */
    fun addSource(id: String, url: String, title: String? = null, parentId: String? = null) {
        pendingSources += PendingSource(
            id = id,
            url = url,
            title = title,
            parentId = parentId ?: toolCallStack.lastOrNull() ?: "root",
        )
    }

    /**
     * Generate a unique tool call ID.
     */
    fun generateToolCallId(toolName: String): String {
        return "call_${toolName}_${toolCounter++}"
    }

    /**
     * Get the current tool call ID (the last one started).
     */
    fun currentToolCallId(): String? = toolCallStack.lastOrNull()

    private fun flushSources() {
        if (pendingSources.isEmpty()) return

        pendingSources.forEach { src ->
            writer.source(
                id = src.id,
                url = src.url,
                title = src.title,
                parentId = src.parentId,
            )
        }
        pendingSources.clear()
    }

    private fun serializeResult(result: Any?): JsonElement {
        return when (result) {
            null -> buildJsonObject { put("content", "No result") }
            is String -> buildJsonObject { put("content", result) }
            is JsonElement -> result
            else -> {
                // Try to serialize if it's a serializable object
                try {
                    json.encodeToJsonElement(result as Any)
                } catch (e: Exception) {
                    buildJsonObject { put("content", result.toString()) }
                }
            }
        }
    }

    private fun FinishReason.toAui(): AuiFinishReason = when (this) {
        FinishReason.STOP -> AuiFinishReason.STOP
        FinishReason.LENGTH -> AuiFinishReason.LENGTH
        FinishReason.TOOL_CALLS -> AuiFinishReason.TOOL_CALLS
        FinishReason.CONTENT_FILTER -> AuiFinishReason.CONTENT_FILTER
        FinishReason.OTHER -> AuiFinishReason.OTHER
    }

    private fun Usage.toAui(): AuiUsage = AuiUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
    )
}
