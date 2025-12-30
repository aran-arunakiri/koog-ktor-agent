package tech.abstracty.agent.ktor

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import tech.abstracty.agent.protocol.StreamBridge
import tech.abstracty.agent.protocol.assistantui.AssistantUIBridge
import tech.abstracty.agent.protocol.openai.OpenAISseBridge
import java.io.Writer

/**
 * Protocol configuration for streaming responses.
 */
sealed class StreamProtocol {
    /**
     * Assistant-UI DataStream protocol.
     *
     * Format: `{code}:{json}\n`
     * Content-Type: text/plain
     */
    data class AssistantUI(
        val json: Json = Json { ignoreUnknownKeys = true }
    ) : StreamProtocol()

    /**
     * OpenAI-compatible SSE protocol.
     *
     * Format: `data: {json}\n\n`
     * Content-Type: text/event-stream
     */
    data class OpenAI(
        val model: String,
        val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    ) : StreamProtocol()
}

/**
 * Stream an agent response using the specified protocol.
 *
 * Example with Assistant-UI:
 * ```kotlin
 * post("/ai/stream") {
 *     call.streamAgent(StreamProtocol.AssistantUI()) { bridge ->
 *         val agent = createAgent(bridge)
 *         agent.run(messages)
 *     }
 * }
 * ```
 *
 * Example with OpenAI SSE:
 * ```kotlin
 * post("/v1/chat/completions") {
 *     call.streamAgent(StreamProtocol.OpenAI(model = request.model)) { bridge ->
 *         val agent = createAgent(bridge)
 *         agent.run(messages)
 *     }
 * }
 * ```
 */
suspend fun RoutingCall.streamAgent(
    protocol: StreamProtocol,
    block: suspend (StreamBridge) -> Unit
) {
    val contentType = when (protocol) {
        is StreamProtocol.AssistantUI -> ContentType.Text.Plain
        is StreamProtocol.OpenAI -> ContentType.Text.EventStream
    }

    response.headers.append(HttpHeaders.CacheControl, "no-cache", false)
    response.headers.append(HttpHeaders.Connection, "keep-alive", false)

    respondTextWriter(contentType = contentType) {
        val bridge: StreamBridge = when (protocol) {
            is StreamProtocol.AssistantUI -> AssistantUIBridge(this, protocol.json)
            is StreamProtocol.OpenAI -> OpenAISseBridge(this as Writer, protocol.model, protocol.json)
        }

        try {
            block(bridge)
        } catch (e: Throwable) {
            bridge.onError(e.message ?: "Unknown error")
        }
    }
}

/**
 * Convenience extension for Assistant-UI protocol with default settings.
 */
suspend fun RoutingCall.streamAssistantUI(
    block: suspend (AssistantUIBridge) -> Unit
) {
    response.headers.append(HttpHeaders.CacheControl, "no-cache", false)

    respondTextWriter(contentType = ContentType.Text.Plain) {
        val bridge = AssistantUIBridge(this)
        try {
            block(bridge)
        } catch (e: Throwable) {
            bridge.onError(e.message ?: "Unknown error")
        }
    }
}

/**
 * Convenience extension for OpenAI SSE protocol.
 */
suspend fun RoutingCall.streamOpenAI(
    model: String,
    block: suspend (OpenAISseBridge) -> Unit
) {
    response.headers.append(HttpHeaders.CacheControl, "no-cache", false)

    respondTextWriter(contentType = ContentType.Text.EventStream) {
        val bridge = OpenAISseBridge(this as Writer, model)
        try {
            block(bridge)
        } catch (e: Throwable) {
            bridge.onError(e.message ?: "Unknown error")
        }
    }
}
