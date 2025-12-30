package tech.abstracty.agent.rag

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kotlinx.serialization.Serializable

/**
 * Arguments for a search tool.
 */
@Serializable
data class SearchArgs(
    val query: String
)

/**
 * Description of a dynamic search tool loaded from configuration.
 */
@Serializable
data class ToolDescription(
    /** Unique tool name/identifier */
    val name: String,

    /** Folder/collection name in Qdrant */
    val folder: String,

    /** Human-readable description for the LLM */
    val description: String,

    /** Optional list of related titles */
    val titles: List<String> = emptyList(),

    /** Optional keywords for discovery */
    val keywords: List<String> = emptyList(),

    /** Optional category for organization */
    val category: String? = null,
)

/**
 * A dynamically created search tool that wraps a vector search function.
 *
 * Used to create RAG tools from configuration files.
 */
class DynamicSearchTool(
    private val toolDescription: ToolDescription,
    private val searchFunction: CollectionSearchFunction,
) : SimpleTool<SearchArgs>() {

    override val name: String = toolDescription.name

    override val description: String = toolDescription.description

    override val argsSerializer = SearchArgs.serializer()

    override val descriptor = ToolDescriptor(
        name = toolDescription.name,
        description = toolDescription.description,
        requiredParameters = listOf(
            ToolParameterDescriptor(
                name = "query",
                description = "The search query",
                type = ToolParameterType.String
            )
        )
    )

    override suspend fun doExecute(args: SearchArgs): String {
        return searchFunction(args.query, toolDescription.folder)
    }

    companion object {
        /**
         * Create a dynamic search tool from a tool description and search service.
         */
        fun create(
            description: ToolDescription,
            searchService: VectorSearchService,
        ): DynamicSearchTool {
            return DynamicSearchTool(
                toolDescription = description,
                searchFunction = searchService.asSearchFunction()
            )
        }

        /**
         * Create multiple search tools from a list of descriptions.
         */
        fun createAll(
            descriptions: List<ToolDescription>,
            searchService: VectorSearchService,
        ): List<DynamicSearchTool> {
            return descriptions.map { create(it, searchService) }
        }
    }
}
