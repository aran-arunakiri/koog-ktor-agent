package tech.abstracty.agent.protocol.assistantui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Assistant-UI DataStream frame types.
 *
 * Format: `{code}:{json}\n`
 * Example: `0:"Hello world"\n`
 */
sealed class Frame {
    /** 0: Text delta - streamed response text */
    @Serializable @SerialName("0")
    data class Text(val textDelta: String) : Frame()

    /** 2: Data - auxiliary per-part data */
    @Serializable @SerialName("2")
    data class Data(val data: List<JsonElement>) : Frame()

    /** 3: Error - top-level error message */
    @Serializable @SerialName("3")
    data class TopLevelError(val message: String) : Frame()

    /** 8: Annotation - auxiliary annotations */
    @Serializable @SerialName("8")
    data class Annotation(val annotations: List<JsonElement>) : Frame()

    /** 9: ToolCall - non-streaming tool call with full args */
    @Serializable @SerialName("9")
    data class ToolCall(
        val toolCallId: String,
        val toolName: String,
        val args: JsonObject,
    ) : Frame()

    /** a: ToolResult - tool execution result */
    @Serializable @SerialName("a")
    data class ToolResult(
        val toolCallId: String,
        val result: JsonElement,
        val artifact: JsonElement? = null,
        val isError: Boolean? = null,
    ) : Frame()

    /** b: ToolBegin - start of tool execution */
    @Serializable @SerialName("b")
    data class ToolBegin(
        val toolCallId: String,
        val toolName: String,
        val parentId: String? = null,
    ) : Frame()

    /** c: ToolArgsDelta - streamed tool arguments */
    @Serializable @SerialName("c")
    data class ToolArgsDelta(
        val toolCallId: String,
        val argsTextDelta: String,
    ) : Frame()

    /** d: FinishMessage - message completion */
    @Serializable @SerialName("d")
    data class FinishMessage(
        val finishReason: AuiFinishReason,
        val usage: AuiUsage,
    ) : Frame()

    /** e: FinishStep - step completion */
    @Serializable @SerialName("e")
    data class FinishStep(
        val finishReason: AuiFinishReason,
        val usage: AuiUsage,
        val isContinued: Boolean,
    ) : Frame()

    /** f: StartStep - step initiation */
    @Serializable @SerialName("f")
    data class StartStep(
        val messageId: String,
    ) : Frame()

    /** g: ReasoningDelta - model reasoning tokens */
    @Serializable @SerialName("g")
    data class ReasoningDelta(val reasoningDelta: String) : Frame()

    /** h: Source - URL citation */
    @Serializable @SerialName("h")
    data class Source(
        val sourceType: String = "url",
        val id: String,
        val url: String,
        val title: String? = null,
        val parentId: String? = null,
    ) : Frame()

    /** i: RedactedReasoning - opaque reasoning blob */
    @Serializable @SerialName("i")
    data class RedactedReasoning(val data: String) : Frame()

    /** k: File - binary resource as base64 */
    @Serializable @SerialName("k")
    data class File(
        val data: String,
        val mimeType: String,
        val parentId: String? = null,
        val filename: String? = null,
    ) : Frame()
}

@Serializable
enum class AuiFinishReason {
    @SerialName("stop") STOP,
    @SerialName("length") LENGTH,
    @SerialName("content-filter") CONTENT_FILTER,
    @SerialName("tool-calls") TOOL_CALLS,
    @SerialName("error") ERROR,
    @SerialName("other") OTHER,
    @SerialName("unknown") UNKNOWN
}

@Serializable
data class AuiUsage(
    val promptTokens: Int,
    val completionTokens: Int,
)
