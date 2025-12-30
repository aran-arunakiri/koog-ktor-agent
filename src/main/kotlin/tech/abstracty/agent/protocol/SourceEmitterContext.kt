package tech.abstracty.agent.protocol

import kotlin.coroutines.CoroutineContext

/**
 * Coroutine context element for emitting sources during tool execution.
 *
 * This stays tenant-agnostic; callers decide the source IDs/URLs.
 */
class SourceEmitterContext(
    val emitter: suspend (String, String, String?) -> Unit
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<SourceEmitterContext>
    override val key: CoroutineContext.Key<SourceEmitterContext> = Key
}
