# koog-ktor-agent

Reusable library for building AI agents with [Koog](https://koog.ai) and [Ktor](https://ktor.io), supporting multiple frontend protocols.

## Features

- **Multi-protocol streaming** - Support for Assistant-UI DataStream and OpenAI SSE formats
- **Koog integration** - Agent builder DSL with streaming support
- **RAG pipeline** - Qdrant vector search with OpenAI embeddings
- **Web search** - Google Custom Search with HTML/PDF content extraction
- **Connection pooling** - Efficient client reuse for Qdrant and HTTP

## Installation

### GitHub Packages

Add the GitHub Packages repository to your `build.gradle.kts`:

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/aran-arunakiri/koog-ktor-agent")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_USERNAME")
            password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("tech.abstracty:koog-ktor-agent:0.2.4")
}
```

### Local Installation

```bash
./gradlew publishToMavenLocal
```

Then in your project:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("tech.abstracty:koog-ktor-agent:0.2.4")
}
```

## Usage

### Assistant-UI Frontend

```kotlin
import tech.abstracty.agent.ktor.streamAssistantUI
import tech.abstracty.agent.agent.StreamingAgentBuilder
import tech.abstracty.agent.protocol.FinishReason
import tech.abstracty.agent.protocol.Usage

routing {
    post("/ai/stream") {
        call.streamAssistantUI { bridge ->
    val agent = StreamingAgentBuilder.create(bridge) {
        apiKey = System.getenv("OPENAI_API_KEY")
        systemPrompt = "You are a helpful assistant"
        strategy = createChatStreamingStrategy(bridge)
        tools {
            +mySearchTool
            +myRagTool
        }
    }

            agent.run(userMessage)
            bridge.onFinish(FinishReason.STOP, Usage(promptTokens = 10, completionTokens = 50))
        }
    }
}
```

### OpenAI-Compatible Frontend (LibreChat)

```kotlin
import tech.abstracty.agent.ktor.streamOpenAI
import tech.abstracty.agent.agent.StreamingAgentBuilder

routing {
    post("/v1/chat/completions") {
        val request = call.receive<OpenAIRequest>()

        call.streamOpenAI(model = request.model) { bridge ->
            val agent = StreamingAgentBuilder.create(bridge) {
                apiKey = System.getenv("OPENAI_API_KEY")
                systemPrompt = agentConfig.systemPrompt
                strategy = createChatStreamingStrategy(bridge)
                tools {
                    +collectionSearchTool
                }
            }

            agent.run(request.messages.last().content)
        }
    }
}
```

### RAG with Qdrant

```kotlin
import tech.abstracty.agent.rag.*
import tech.abstracty.agent.rag.tools.*

// Configure RAG
val ragConfig = RagConfig(
    tenantId = "my-app",
    qdrantHost = "localhost",
    qdrantGrpcPort = 6334,
    openAIApiKey = System.getenv("OPENAI_API_KEY"),
    embeddingModel = "text-embedding-3-small",
    topK = 5,
)

// Create search service
val ragClientFactory = DefaultRagClientFactory(ragConfig)
val searchService = CollectionSearchService(ragConfig, ragClientFactory)

// Load tool descriptions from JSON
val loader = FileBasedToolDescriptionRepository("/path/to/tool_descriptions")
val descriptions = loader.getDescriptions("tenant-id")

// Create dynamic search tools
val ragTools = descriptions.map { description ->
    DynamicSearchTool(description, searchService::searchInCollection)
}

// Use in agent
StreamingAgentBuilder.create(bridge) {
    tools {
        ragTools.forEach { +it }
    }
}
```

### Web Search Tool

```kotlin
import tech.abstracty.agent.tools.WebSearchTool

// From environment variables
val searchTool = WebSearchTool.fromEnvironment(
    site = "example.com",  // Optional: restrict to domain
    geolocation = "NL"
)

// Or with explicit config
val searchTool = WebSearchTool(
    apiKey = "your-google-api-key",
    cx = "your-custom-search-engine-id",
    site = "example.com",
    geolocation = "NL"
)
```

## Protocol Support

### Assistant-UI DataStream

Format: `{code}:{json}\n`

| Code | Frame Type | Description |
|------|------------|-------------|
| `0` | TextDelta | Streamed response text |
| `b` | ToolBegin | Start of tool execution |
| `a` | ToolResult | Tool execution result |
| `h` | Source | URL citation |
| `d` | FinishMessage | Message completion |
| `3` | Error | Error message |

### OpenAI SSE

Format: `data: {json}\n\n`

Compatible with OpenAI's streaming chat completions API, used by LibreChat and similar frontends.

## Configuration

### Environment Variables

| Variable | Description | Required |
|----------|-------------|----------|
| `OPENAI_API_KEY` | OpenAI API key for LLM and embeddings | Yes |
| `GOOGLE_API_KEY` | Google Custom Search API key | For web search |
| `GOOGLE_CX` | Google Custom Search Engine ID | For web search |
| `QDRANT_HOST` | Qdrant server host | For RAG |
| `QDRANT_GRPC_PORT` | Qdrant gRPC port (default: 6334) | For RAG |

## License

MIT License - see [LICENSE](LICENSE) for details.
