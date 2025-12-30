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

/**
 * Helper to map search hits without tying to any response schema.
 */
fun <T> mapSearchHits(
    hits: List<SearchHit>,
    mapper: (SearchHit) -> T
): List<T> {
    return hits.map(mapper)
}
