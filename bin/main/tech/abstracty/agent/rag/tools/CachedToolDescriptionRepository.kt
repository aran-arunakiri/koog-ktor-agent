package tech.abstracty.agent.rag.tools

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class CachedToolDescriptionRepository(
    private val delegate: ToolDescriptionRepository
) : ToolDescriptionRepository {

    companion object {
        private val logger = LoggerFactory.getLogger(CachedToolDescriptionRepository::class.java)
        private val cache = ConcurrentHashMap<String, CachedData>()

        private data class CachedData(
            val descriptions: List<ToolDescription>,
            val lastModified: Long
        )
    }

    override fun getDescriptions(tenantId: String): List<ToolDescription> {
        val currentModified = delegate.getLastModified(tenantId)
        val cached = cache[tenantId]

        return if (cached != null && cached.lastModified >= currentModified) {
            logger.debug("Using cached tool descriptions for tenant: $tenantId")
            cached.descriptions
        } else {
            logger.info("Loading tool descriptions from disk for tenant: $tenantId (cache miss or file updated)")
            val descriptions = delegate.getDescriptions(tenantId)
            cache[tenantId] = CachedData(descriptions, currentModified)
            descriptions
        }
    }

    override fun getLastModified(tenantId: String): Long {
        return delegate.getLastModified(tenantId)
    }

    fun clearCache(tenantId: String) {
        cache.remove(tenantId)
        logger.info("Cleared cache for tenant: $tenantId")
    }

    fun clearAllCache() {
        cache.clear()
        logger.info("Cleared all cached tool descriptions")
    }
}
