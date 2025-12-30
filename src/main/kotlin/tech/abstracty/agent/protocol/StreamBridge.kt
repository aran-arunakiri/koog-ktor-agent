package tech.abstracty.agent.protocol

import kotlinx.serialization.json.JsonElement

/**
 * Core abstraction for streaming AI agent responses to any frontend.
 *
 * Implementations translate agent events (text deltas, tool calls, etc.)
 * into protocol-specific formats like Assistant-UI DataStream or OpenAI SSE.
 *
 * Example usage:
 * ```kotlin
 * call.streamAgent(AssistantUIProtocol) { bridge ->
 *     val agent = createAgent(bridge)
 *     agent.run(messages)
 * }
 * ```
 */
interface StreamBridge {

    /**
     * Called when the LLM produces a text delta (streaming token).
     */
    suspend fun onTextDelta(delta: String)

    /**
     * Called when a tool call begins.
     *
     * @param callId Unique identifier for this tool call
     * @param toolName Name of the tool being called
     * @param args Optional JSON arguments (may be streamed later)
     */
    suspend fun onToolCallStart(callId: String, toolName: String, args: String? = null)

    /**
     * Called when streaming tool call arguments.
     *
     * @param callId The tool call ID
     * @param argsDelta Partial JSON arguments
     */
    suspend fun onToolCallArgsDelta(callId: String, argsDelta: String) {}

    /**
     * Called when a tool call completes with a result.
     *
     * @param callId The tool call ID
     * @param result The tool result (will be serialized appropriately)
     * @param isError Whether this result represents an error
     */
    suspend fun onToolCallResult(callId: String, result: Any?, isError: Boolean = false)

    /**
     * Called when a source/citation should be added.
     *
     * @param id Unique identifier for the source
     * @param url The source URL
     * @param title Optional title for the source
     */
    suspend fun onSource(id: String, url: String, title: String? = null) {}

    /**
     * Called when the response is complete.
     *
     * @param reason Why the response finished
     * @param usage Optional token usage statistics
     */
    suspend fun onFinish(reason: FinishReason, usage: Usage? = null)

    /**
     * Called when an error occurs.
     *
     * @param message Error message to display
     */
    suspend fun onError(message: String)
}

/**
 * Reason why the LLM response finished.
 */
enum class FinishReason {
    /** Normal completion */
    STOP,
    /** Stopped due to length limit */
    LENGTH,
    /** Model wants to call tools */
    TOOL_CALLS,
    /** Content was filtered */
    CONTENT_FILTER,
    /** Unknown or other reason */
    OTHER
}

/**
 * Token usage statistics.
 */
data class Usage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = promptTokens + completionTokens
)
