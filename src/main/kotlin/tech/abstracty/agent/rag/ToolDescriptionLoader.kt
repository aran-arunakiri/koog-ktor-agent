package tech.abstracty.agent.rag

import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Loader for tool descriptions from JSON files.
 *
 * Supports caching with file modification time checks.
 */
class ToolDescriptionLoader(
    private val basePath: String = "/app/dynamic_config/tool_descriptions",
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private data class CachedData(
        val descriptions: List<ToolDescription>,
        val lastModified: Long,
    )

    private val cache = ConcurrentHashMap<String, CachedData>()

    /**
     * Load tool descriptions for a tenant.
     * Returns cached data if the file hasn't been modified.
     *
     * @param tenantId The tenant identifier
     * @return List of tool descriptions, or empty list if file doesn't exist
     */
    fun load(tenantId: String): List<ToolDescription> {
        val file = File("$basePath/$tenantId.json")
        if (!file.exists()) return emptyList()

        val currentModified = file.lastModified()
        val cached = cache[tenantId]

        return if (cached != null && cached.lastModified >= currentModified) {
            cached.descriptions
        } else {
            val descriptions = json.decodeFromString<List<ToolDescription>>(file.readText())
            cache[tenantId] = CachedData(descriptions, currentModified)
            descriptions
        }
    }

    /**
     * Load tool descriptions from a resource in the classpath.
     *
     * @param resourcePath The resource path (e.g., "/tools.json")
     * @return List of tool descriptions
     */
    fun loadFromResource(resourcePath: String): List<ToolDescription> {
        val inputStream = this::class.java.getResourceAsStream(resourcePath)
            ?: throw IllegalArgumentException("Resource not found: $resourcePath")

        val content = inputStream.bufferedReader().use { it.readText() }
        return json.decodeFromString(content)
    }

    /**
     * Clear the cache for a specific tenant.
     */
    fun clearCache(tenantId: String) {
        cache.remove(tenantId)
    }

    /**
     * Clear all cached data.
     */
    fun clearAllCache() {
        cache.clear()
    }

    companion object {
        private val defaultLoader = ToolDescriptionLoader()

        /**
         * Get the default loader instance.
         */
        fun default(): ToolDescriptionLoader = defaultLoader
    }
}
