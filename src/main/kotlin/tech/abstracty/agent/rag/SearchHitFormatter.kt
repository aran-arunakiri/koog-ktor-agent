package tech.abstracty.agent.rag

/**
 * Helper to format search hits into a single string using a custom formatter.
 */
fun formatSearchHits(
    hits: List<SearchHit>,
    itemFormatter: (SearchHit) -> String,
    separator: String = "\n\n"
): String {
    return hits.joinToString(separator) { itemFormatter(it) }
}
