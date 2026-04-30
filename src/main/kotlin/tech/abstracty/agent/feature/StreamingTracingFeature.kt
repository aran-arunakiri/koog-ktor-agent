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
        // Per-eventId state for the duration of a stream:
        //  - text buffer accumulates TextDelta payloads
        //  - end frame holds the terminal StreamFrame.End so we can read its usage metaInfo
        //    when emitting onLLMCallCompleted (without it OTel sees 0 tokens because
        //    ResponseMetaInfo.Empty has null input/output token counts).
        val responseBuffers = ConcurrentHashMap<String, StringBuilder>()
        val endFrames = ConcurrentHashMap<String, ai.koog.prompt.streaming.StreamFrame.End>()

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
            when (val frame = ctx.streamFrame) {
                is ai.koog.prompt.streaming.StreamFrame.TextDelta ->
                    responseBuffers[ctx.eventId]?.append(frame.text)
                is ai.koog.prompt.streaming.StreamFrame.End ->
                    endFrames[ctx.eventId] = frame
                else -> {}
            }
        }

        pipeline.interceptLLMStreamingCompleted(this) { ctx ->
            val text = responseBuffers.remove(ctx.eventId)?.toString() ?: ""
            // Use the End frame's metaInfo when available — OpenAI-family clients populate
            // it from `stream_options.include_usage` automatically. Falls back to Empty when
            // the upstream client doesn't emit a populated End frame.
            val metaInfo = endFrames.remove(ctx.eventId)?.metaInfo
                ?: ai.koog.prompt.message.ResponseMetaInfo.Empty
            val responses: List<ai.koog.prompt.message.Message.Response> = if (text.isNotEmpty()) {
                listOf(
                    ai.koog.prompt.message.Message.Assistant(
                        content = text,
                        metaInfo = metaInfo,
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
