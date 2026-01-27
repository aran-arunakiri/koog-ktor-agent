package tech.abstracty.agent.rag.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import tech.abstracty.agent.rag.CollectionSearchFunction

class DynamicSearchTool(
    private val toolDescription: ToolDescription,
    private val searchFunction: CollectionSearchFunction
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
                description = "Search query",
                type = ToolParameterType.String
            )
        )
    )

    override suspend fun doExecute(args: SearchArgs): String {
        return searchFunction(args.query, toolDescription.folder)
    }
}
