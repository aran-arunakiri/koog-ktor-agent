package tech.abstracty.agent.rag.tools

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

interface ToolDescriptionRepository {
    fun getDescriptions(tenantId: String): List<ToolDescription>
    fun getLastModified(tenantId: String): Long
}

class FileBasedToolDescriptionRepository(
    private val basePath: String = "/app/dynamic_config/tool_descriptions"
) : ToolDescriptionRepository {

    companion object {
        private val logger = LoggerFactory.getLogger(FileBasedToolDescriptionRepository::class.java)
    }

    override fun getDescriptions(tenantId: String): List<ToolDescription> {
        val filePath = "$basePath/$tenantId.json"
        val file = File(filePath)

        if (!file.exists()) {
            logger.debug("Tool descriptions file not found for tenant '$tenantId' at path: $filePath")
            return emptyList()
        }

        return try {
            val jsonContent = file.readText()
            val descriptions = Json.decodeFromString<List<ToolDescription>>(jsonContent)
            logger.info("Loaded ${descriptions.size} tool descriptions for tenant '$tenantId'")
            descriptions
        } catch (e: Exception) {
            logger.error("Error loading tool descriptions for tenant '$tenantId' from $filePath", e)
            emptyList()
        }
    }

    override fun getLastModified(tenantId: String): Long {
        val file = File("$basePath/$tenantId.json")
        return if (file.exists()) file.lastModified() else 0L
    }
}
