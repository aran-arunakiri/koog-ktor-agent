package tech.abstracty.agent.protocol.assistantui

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import tech.abstracty.agent.protocol.FinishReason
import tech.abstracty.agent.protocol.StreamBridge
import tech.abstracty.agent.protocol.Usage

/**
 * StreamBridge implementation for Assistant-UI DataStream protocol.
 *
 * Thread-safe: all public methods are serialized via a coroutine [Mutex]
 * so parallel tool calls never interleave frames on the underlying writer.
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
    private val mutex = Mutex()

    private data class PendingSource(
        val id: String,
        val url: String,
        val title: String?,
        val parentId: String,
    )

    private val pendingSources = mutableListOf<PendingSource>()

    override suspend fun onTextDelta(delta: String) = mutex.withLock {
        if (delta.isNotEmpty()) {
            writer.text(delta)
        }
    }

    override suspend fun onToolCallStart(callId: String, toolName: String, args: String?) = mutex.withLock {
        writer.toolBegin(callId = callId, toolName = toolName, parentId = null)
    }

    override suspend fun onToolCallArgsDelta(callId: String, argsDelta: String) = mutex.withLock {
        writer.toolArgsDelta(callId, argsDelta)
    }

    override suspend fun onToolCallResult(callId: String, result: Any?, isError: Boolean) = mutex.withLock {
        writer.toolResult(
            callId = callId,
            result = serializeResult(result),
            artifact = null,
            isError = if (isError) true else null,
        )
    }

    override suspend fun onSource(id: String, url: String, title: String?) = mutex.withLock {
        pendingSources += PendingSource(id = id, url = url, title = title, parentId = "root")
    }

    override suspend fun onFinish(reason: FinishReason, usage: Usage?) = mutex.withLock {
        flushSources()
        writer.finishMessage(
            finish = reason.toAui(),
            usage = (usage ?: Usage()).toAui()
        )
    }

    override suspend fun onError(message: String) = mutex.withLock {
        writer.error(message)
    }

    /**
     * Add a source/citation that will be flushed at the end of the turn.
     */
    suspend fun addSource(id: String, url: String, title: String? = null, parentId: String? = null) = mutex.withLock {
        pendingSources += PendingSource(id = id, url = url, title = title, parentId = parentId ?: "root")
    }

    private fun flushSources() {
        if (pendingSources.isEmpty()) return

        // Deduplicate by URL to avoid showing the same source multiple times
        pendingSources.distinctBy { it.url }.forEach { src ->
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
