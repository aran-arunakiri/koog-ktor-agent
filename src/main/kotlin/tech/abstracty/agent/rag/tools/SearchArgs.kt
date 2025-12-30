package tech.abstracty.agent.rag.tools

import ai.koog.agents.core.tools.ToolArgs
import kotlinx.serialization.Serializable

@Serializable
data class SearchArgs(
    val query: String
) : ToolArgs
