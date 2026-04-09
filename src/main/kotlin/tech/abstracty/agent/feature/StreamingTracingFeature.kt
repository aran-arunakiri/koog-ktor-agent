package tech.abstracty.agent.feature

import ai.koog.agents.core.agent.entity.AIAgentStorageKey
import ai.koog.agents.core.feature.AIAgentGraphFeature
import ai.koog.agents.core.feature.config.FeatureConfig
import ai.koog.agents.core.feature.handler.llm.LLMCallCompletedContext
import ai.koog.agents.core.feature.handler.llm.LLMCallStartingContext
import ai.koog.agents.core.feature.pipeline.AIAgentGraphPipeline
import ai.koog.prompt.dsl.ModerationResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges streaming pipeline events to LLM call pipeline events so that the
 * OpenTelemetry feature creates properly-parented inference spans for streaming
 * nodes (e.g. nodeRedirect, nodeDisambiguate).
 *
 * Without this, requestLLMStreaming() only fires onLLMStreamingStarting/Completed
 * which the OTel feature does not hook into for span creation. This feature
 * intercepts those streaming events and fires onLLMCallStarting/Completed,
 * which the OTel feature does hook into — resulting in correctly-nested
 * inference spans in Langfuse.
 */
object StreamingTracingFeature : AIAgentGraphFeature<StreamingTracingFeature.Config, Unit> {

    class Config : FeatureConfig()

    override val key = AIAgentStorageKey<Unit>("StreamingTracingFeature")

    override fun createInitialConfig() = Config()

    override fun install(config: Config, pipeline: AIAgentGraphPipeline) {
        // Track accumulated response text per eventId so we can pass it to onLLMCallCompleted
        val responseBuffers = ConcurrentHashMap<String, StringBuilder>()

        pipeline.interceptLLMStreamingStarting(this) { ctx ->
            responseBuffers[ctx.eventId] = StringBuilder()
            ctx.context.pipeline.onLLMCallStarting(
                eventId = ctx.eventId,
                executionInfo = ctx.executionInfo,
                runId = ctx.runId,
                prompt = ctx.prompt,
                model = ctx.model,
                tools = ctx.tools,
                context = ctx.context,
            )
        }

        pipeline.interceptLLMStreamingFrameReceived(this) { ctx ->
            val frame = ctx.streamFrame
            // Accumulate text deltas so we can pass the full response on completion
            if (frame is ai.koog.prompt.streaming.StreamFrame.TextDelta) {
                responseBuffers[ctx.eventId]?.append(frame.text)
            }
        }

        pipeline.interceptLLMStreamingCompleted(this) { ctx ->
            val text = responseBuffers.remove(ctx.eventId)?.toString() ?: ""
            val responses: List<ai.koog.prompt.message.Message.Response> = if (text.isNotEmpty()) {
                listOf(
                    ai.koog.prompt.message.Message.Assistant(
                        content = text,
                        metaInfo = ai.koog.prompt.message.ResponseMetaInfo.Empty,
                    )
                )
            } else emptyList()

            ctx.context.pipeline.onLLMCallCompleted(
                eventId = ctx.eventId,
                executionInfo = ctx.executionInfo,
                runId = ctx.runId,
                prompt = ctx.prompt,
                model = ctx.model,
                tools = ctx.tools,
                responses = responses,
                moderationResponse = ModerationResult(isHarmful = false, categories = emptyMap()),
                context = ctx.context,
            )
        }
    }
}
