package tech.abstracty.agent.rag.tools

import kotlinx.serialization.Serializable

@Serializable
data class ToolDescription(
    val name: String,
    val description: String,
    val titles: List<String> = emptyList(),
    val folder: String,
    val keywords: List<String> = emptyList(),
    val category: String? = null
)
