package tech.abstracty.agent.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolArgs
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.jsoup.Jsoup
import tech.abstracty.agent.http.ConnectionPoolManager
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.URLBuilder

class WebSearchTool(
    private val apiKey: String,
    private val cx: String,
    private val site: String? = null,
    private val geolocation: String = "NL"
) : Tool<WebSearchTool.Args, WebSearchTool.Result>() {

    companion object {
        fun fromEnvironment(
            site: String? = null,
            geolocation: String = "NL"
        ): WebSearchTool {
            val apiKey = System.getenv("GOOGLE_API_KEY") ?: ""
            val cx = System.getenv("GOOGLE_CX") ?: ""
            return WebSearchTool(apiKey = apiKey, cx = cx, site = site, geolocation = geolocation)
        }
    }

    @Serializable
    data class Args(
        val query: String,
        val searchType: String? = null,
        val fetchContent: Boolean = true,
        val maxFetch: Int = 1,
        val maxChars: Int = 20000
    ) : ToolArgs

    @Serializable
    data class Item(
        val title: String,
        val link: String,
        val snippet: String? = null,
        val mime: String? = null,
        val content: String? = null,
        val httpStatus: Int? = null,
        val contentType: String? = null
    )

    @Serializable
    data class Result(
        val totalResults: String?,
        val items: List<Item>
    ) : ToolResult.JSONSerializable<Result> {
        override fun getSerializer(): KSerializer<Result> = serializer()
    }

    override val argsSerializer = Args.serializer()
    override val resultSerializer: KSerializer<Result> = Result.serializer()
    override val description =
        "Search the web via Google Programmable Search (JSON API), fixed to gl=NL and optionally scoped to a domain. " +
            "Fetches and extracts HTML or PDF text for the top results."

    override val descriptor = ToolDescriptor(
        name = "WebSearchTool",
        description = description,
        requiredParameters = listOf(
            ToolParameterDescriptor("query", "Query string", ToolParameterType.String)
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor("searchType", "Use 'image' for image search", ToolParameterType.String),
            ToolParameterDescriptor("fetchContent", "Fetch and extract page text", ToolParameterType.Boolean),
            ToolParameterDescriptor("maxFetch", "Number of results to fetch", ToolParameterType.Integer),
            ToolParameterDescriptor("maxChars", "Max characters of fetched text", ToolParameterType.Integer)
        )
    )

    private val apiClient = ConnectionPoolManager.getKtorClient()
    private val javaHttpClient = ConnectionPoolManager.getHttpClient()

    override suspend fun execute(args: Args): Result {
        require(apiKey.isNotBlank()) { "Missing Google API key" }
        require(cx.isNotBlank()) { "Missing Google CX" }

        val base = "https://www.googleapis.com/customsearch/v1"
        val url = URLBuilder(base).apply {
            parameters.append("key", apiKey)
            parameters.append("cx", cx)
            parameters.append("q", args.query)
            parameters.append("gl", geolocation)
            args.searchType?.let { parameters.append("searchType", it) }
            site?.let {
                parameters.append("siteSearch", it)
                parameters.append("siteSearchFilter", "i")
            }
        }.buildString()

        val response = withContext(Dispatchers.IO) { apiClient.get(url) }
        val resp: JsonObject = response.body()

        val total = resp["searchInformation"]
            ?.jsonObject
            ?.get("totalResults")
            ?.toString()
            ?.trim('"')

        val allItems = resp["items"]
            ?.jsonArray
            ?.map { it.jsonObject }
            ?.map {
                Item(
                    title = it["title"]?.toString()?.trim('"') ?: "",
                    link = it["link"]?.toString()?.trim('"') ?: "",
                    snippet = it["snippet"]?.toString()?.trim('"'),
                    mime = it["mime"]?.toString()?.trim('"')
                )
            } ?: emptyList()

        if (allItems.isEmpty()) {
            return Result(totalResults = total, items = emptyList())
        }

        val max = args.maxFetch.coerceAtLeast(1).coerceAtMost(allItems.size)
        val limitedItems = allItems.take(max)

        val fetchedItems =
            if (args.fetchContent) {
                coroutineScope {
                    limitedItems.map { item ->
                        async {
                            val fetched = fetchPage(item.link, args.maxChars)
                            if (fetched?.body != null) {
                                item.copy(
                                    content = fetched.body,
                                    httpStatus = fetched.status,
                                    contentType = fetched.contentType,
                                    snippet = null
                                )
                            } else {
                                null
                            }
                        }
                    }.awaitAll()
                }.filterNotNull()
            } else {
                limitedItems
            }

        return Result(
            totalResults = total,
            items = fetchedItems
        )
    }

    private suspend fun fetchPage(url: String, maxChars: Int): FetchedText? =
        withContext(Dispatchers.IO) {
            try {
                withTimeout(15_000) {
                    val request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("User-Agent", "curl/8.1.2")
                        .header("Accept", "*/*")
                        .GET()
                        .build()

                    val response = javaHttpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
                    val status = response.statusCode()
                    val contentType = response.headers().firstValue("content-type").orElse("")

                    if (status >= 400) return@withTimeout FetchedText(null, status, contentType)

                    val bodyBytes = response.body()
                    val ct = contentType.lowercase()
                    val extractedText = when {
                        "text/html" in ct -> extractHtmlText(bodyBytes, maxChars)
                        "application/pdf" in ct -> extractPdfText(bodyBytes, maxChars)
                        ct.startsWith("text/") -> String(bodyBytes).take(maxChars)
                        else -> null
                    }

                    FetchedText(extractedText, status, contentType)
                }
            } catch (_: Exception) {
                null
            }
        }

    private fun extractHtmlText(bodyBytes: ByteArray, maxChars: Int): String {
        val doc = Jsoup.parse(String(bodyBytes))
        doc.select("script, style, nav, header, footer, .nav, .menu, .sidebar, #cookie-banner").remove()
        val main = doc.select("main, article, .content, #content, [role=main]").firstOrNull()
        return (main ?: doc.body()).text().replace("\\s+".toRegex(), " ").trim().take(maxChars)
    }

    private fun extractPdfText(bodyBytes: ByteArray, maxChars: Int): String? {
        return try {
            Loader.loadPDF(bodyBytes).use { document ->
                val stripper = PDFTextStripper()
                stripper.getText(document).replace("\\s+".toRegex(), " ").trim().take(maxChars)
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class FetchedText(
        val body: String?,
        val status: Int,
        val contentType: String
    )
}
