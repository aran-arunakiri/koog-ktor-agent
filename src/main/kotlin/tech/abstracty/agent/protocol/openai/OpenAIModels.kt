package tech.abstracty.agent.protocol.openai

import kotlinx.serialization.Serializable

/**
 * OpenAI-compatible streaming response models.
 *
 * These match the OpenAI API format for SSE streaming responses.
 */

@Serializable
data class StreamingChunk(
    val id: String,
    val `object`: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<StreamingChoice>,
)

@Serializable
data class StreamingChoice(
    val index: Int = 0,
    val delta: Delta,
    val finish_reason: String? = null,
)

@Serializable
data class Delta(
    val role: String? = null,
    val content: String? = null,
    val tool_calls: List<DeltaToolCall>? = null,
)

@Serializable
data class DeltaToolCall(
    val index: Int,
    val id: String? = null,
    val type: String? = null,
    val function: DeltaFunctionCall? = null,
)

@Serializable
data class DeltaFunctionCall(
    val name: String? = null,
    val arguments: String? = null,
)
