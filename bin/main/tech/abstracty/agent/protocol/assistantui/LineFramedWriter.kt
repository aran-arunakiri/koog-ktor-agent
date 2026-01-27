package tech.abstracty.agent.protocol.assistantui

import kotlinx.serialization.json.*
import java.io.Writer

/**
 * Low-level writer for Assistant-UI DataStream frames.
 *
 * Format: `{code}:{json}\n`
 *
 * Each frame is immediately flushed to support streaming.
 */
class LineFramedWriter(
    private val out: Appendable,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    private fun emit(code: String, payload: JsonElement) {
        out.append(code).append(':')
            .append(json.encodeToString(JsonElement.serializer(), payload))
            .append('\n')
        if (out is Writer) out.flush()
    }

    fun startStep(messageId: String) =
        emit("f", buildJsonObject { put("messageId", messageId) })

    fun finishStep(finish: AuiFinishReason, usage: AuiUsage, isContinued: Boolean) =
        emit("e", buildJsonObject {
            put("finishReason", Json.encodeToJsonElement(finish))
            put("usage", Json.encodeToJsonElement(usage))
            put("isContinued", isContinued)
        })

    fun finishMessage(finish: AuiFinishReason, usage: AuiUsage) =
        emit("d", buildJsonObject {
            put("finishReason", Json.encodeToJsonElement(finish))
            put("usage", Json.encodeToJsonElement(usage))
        })

    fun text(delta: String) = emit("0", Json.encodeToJsonElement(delta))

    fun reasoning(delta: String) = emit("g", Json.encodeToJsonElement(delta))

    fun data(vararg items: JsonElement) =
        emit("2", Json.encodeToJsonElement(items.toList()))

    fun annotation(vararg items: JsonElement) =
        emit("8", Json.encodeToJsonElement(items.toList()))

    fun toolBegin(callId: String, toolName: String, parentId: String? = null) =
        emit("b", buildJsonObject {
            put("toolCallId", callId)
            put("toolName", toolName)
            if (parentId != null) put("parentId", parentId)
        })

    fun toolArgsDelta(callId: String, argsTextDelta: String) =
        emit("c", buildJsonObject {
            put("toolCallId", callId)
            put("argsTextDelta", argsTextDelta)
        })

    fun toolCall(callId: String, toolName: String, args: JsonObject) =
        emit("9", buildJsonObject {
            put("toolCallId", callId)
            put("toolName", toolName)
            put("args", args)
        })

    fun toolResult(callId: String, result: JsonElement, artifact: JsonElement? = null, isError: Boolean? = null) =
        emit("a", buildJsonObject {
            put("toolCallId", callId)
            put("result", result)
            if (artifact != null) put("artifact", artifact)
            if (isError != null) put("isError", isError)
        })

    fun source(id: String, url: String, title: String? = null, parentId: String? = null, sourceType: String = "url") =
        emit("h", buildJsonObject {
            put("sourceType", sourceType)
            put("id", id)
            put("url", url)
            if (!title.isNullOrBlank()) put("title", title)
            if (parentId != null) put("parentId", parentId)
        })

    fun file(base64: String, mimeType: String, parentId: String? = null, filename: String? = null) =
        emit("k", buildJsonObject {
            put("data", base64)
            put("mimeType", mimeType)
            if (parentId != null) put("parentId", parentId)
            if (!filename.isNullOrBlank()) put("filename", filename)
        })

    fun error(message: String) = emit("3", Json.encodeToJsonElement(message))
}
