package tech.abstracty.agent.rag.tools

import ai.koog.agents.core.tools.SimpleTool
import tech.abstracty.agent.rag.CollectionSearchFunction

class DynamicSearchTool(
    private val toolDescription: ToolDescription,
    private val searchFunction: CollectionSearchFunction
) : SimpleTool<SearchArgs>(
    argsSerializer = SearchArgs.serializer(),
    name = toolDescription.name,
    description = toolDescription.description
) {
    override suspend fun execute(args: SearchArgs): String {
        return searchFunction(args.query, toolDescription.folder)
    }
}
