# Java MCP Server Tutorial — Official MCP Java SDK

> **Scope:** Maven · Java 21 · Official SDK (`io.modelcontextprotocol.sdk`)  
> **Covers:** Tools · Resources · Prompts · Sampling · stdio · HTTP+SSE · Streamable HTTP  
> **Goal:** A working PoC test-bed that exercises every MCP primitive across all three transports.

---

## Table of Contents

1. [Background and SDK Overview](#1-background-and-sdk-overview)
2. [Project Setup — Maven POM](#2-project-setup--maven-pom)
3. [Core Concepts Primer](#3-core-concepts-primer)
4. [Implementing Tools](#4-implementing-tools)
5. [Implementing Resources](#5-implementing-resources)
6. [Implementing Prompts](#6-implementing-prompts)
7. [Implementing Sampling](#7-implementing-sampling)
8. [Transport 1 — stdio](#8-transport-1--stdio)
9. [Transport 2 — HTTP + SSE (legacy, spec 2024-11-05)](#9-transport-2--http--sse-legacy-spec-2024-11-05)
10. [Transport 3 — Streamable HTTP (spec 2025-03-26)](#10-transport-3--streamable-http-spec-2025-03-26)
11. [Registering the Server with a Client](#11-registering-the-server-with-a-client)
12. [Testing and Inspection — MCP Inspector vs Java-native](#12-testing-and-inspection--mcp-inspector-vs-java-native)
13. [Sync vs Async API, Env-var Config, listChanged Notifications](#13-sync-vs-async-api-env-var-config-listchanged-notifications)
14. [Key Takeaways and Next Steps](#14-key-takeaways-and-next-steps)

---

## 1. Background and SDK Overview

The **Official MCP Java SDK** lives at:

```
GitHub : github.com/modelcontextprotocol/java-sdk
Maven  : io.modelcontextprotocol.sdk:mcp
License: MIT
Java   : 17+  (we use 21 LTS)
```

It is maintained inside the same GitHub organisation as the TypeScript and Python SDKs, so it tracks the spec directly. The SDK ships two dependency artifacts:

| Artifact | Purpose |
|---|---|
| `io.modelcontextprotocol.sdk:mcp` | Core SDK — all primitives, sync + reactive flavors |
| `io.modelcontextprotocol.sdk:mcp-spring-webflux` | Reactor/WebFlux HTTP transport helpers (optional) |

The SDK models are generated from the MCP JSON Schema, so every class name matches the spec exactly. You build a server by:

1. Declaring `ServerCapabilities` (which primitives you support).
2. Creating `McpServerFeatures` — registering tools, resources, and prompts.
3. Wrapping it in a transport (stdio or HTTP).
4. Starting the server.

---

## 2. Project Setup — Maven POM

Create a new Maven project. The minimal `pom.xml` for a PoC test-bed:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>mcp-poc</artifactId>
    <version>1.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <properties>
        <java.version>21</java.version>
        <maven.compiler.release>21</maven.compiler.release>
        <!-- Check Maven Central for the latest: search "io.modelcontextprotocol.sdk" -->
        <mcp.version>0.9.0</mcp.version>
    </properties>

    <dependencies>
        <!-- MCP Core SDK -->
        <dependency>
            <groupId>io.modelcontextprotocol.sdk</groupId>
            <artifactId>mcp</artifactId>
            <version>${mcp.version}</version>
        </dependency>

        <!-- HTTP transports (SSE + Streamable HTTP) need an HTTP server.
             We use the SDK's built-in Reactor Netty bridge here.         -->
        <dependency>
            <groupId>io.modelcontextprotocol.sdk</groupId>
            <artifactId>mcp-spring-webflux</artifactId>
            <version>${mcp.version}</version>
        </dependency>

        <!-- Reactor Netty server (pulled transitively, listed explicitly) -->
        <dependency>
            <groupId>io.projectreactor.netty</groupId>
            <artifactId>reactor-netty-http</artifactId>
            <version>1.1.21</version>
        </dependency>

        <!-- Jackson for JSON schema building -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>2.17.1</version>
        </dependency>

        <!-- SLF4J + Logback for structured logging -->
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
            <version>2.0.13</version>
        </dependency>
        <dependency>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-classic</artifactId>
            <version>1.5.6</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- Fat-JAR so we can run with: java -jar mcp-poc.jar -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>3.6.0</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                        <configuration>
                            <createDependencyReducedPom>false</createDependencyReducedPom>
                            <transformers>
                                <transformer implementation=
                                    "org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                    <!-- Change to whichever Main you want to run -->
                                    <mainClass>com.example.mcp.StdioServer</mainClass>
                                </transformer>
                            </transformers>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

> **Version note:** Always verify `mcp.version` on
> [Maven Central](https://central.sonatype.com/artifact/io.modelcontextprotocol.sdk/mcp)
> before starting. Use the highest non-SNAPSHOT release.

---

## 3. Core Concepts Primer

### The four MCP primitives

| Primitive | What the server exposes | LLM usage |
|---|---|---|
| **Tool** | Callable function with typed JSON input schema | LLM decides to call it; host executes it |
| **Resource** | Readable data at a URI (text or binary) | LLM or user requests contents |
| **Prompt** | Parameterised message template | User picks a prompt; host fills params |
| **Sampling** | Server asks the LLM to generate text | Server-driven inference (rare but powerful) |

### SDK class map

```
McpServer                  ← entry point, builds the server
McpServerFeatures          ← container for tools/resources/prompts/sampling
ServerCapabilities         ← declares what the server supports
McpSchema.Tool             ← tool descriptor (name, description, input schema)
McpSchema.Resource         ← resource descriptor (uri, name, mime type)
McpSchema.ResourceTemplate ← URI-template resource descriptor
McpSchema.Prompt           ← prompt descriptor
McpSchema.GetPromptResult  ← prompt response (list of messages)
McpSchema.CreateMessageResult ← sampling response
StdioServerTransport       ← stdio transport
WebFluxSseServerTransport  ← HTTP+SSE transport
WebFluxStreamableHttpServerTransport ← Streamable HTTP transport
```

---

## 4. Implementing Tools

A **tool** is a function the LLM can call. You provide:
- A name and description (shown to the LLM).
- A JSON Schema describing the input parameters.
- A handler `Function<CallToolRequest, CallToolResult>`.

### 4.1 Helper: building a JSON Schema

The SDK accepts the input schema as a `Map<String, Object>` that mirrors a JSON Schema object.

```java
// src/main/java/com/example/mcp/SchemaBuilder.java
package com.example.mcp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal helper to build JSON Schema maps for tool input schemas.
 * The SDK serialises these directly into the protocol message.
 */
public final class SchemaBuilder {

    private SchemaBuilder() {}

    /** Create an object schema with the given required string properties. */
    public static Map<String, Object> objectSchema(
            String description,
            Map<String, String> stringProperties,   // name -> description
            List<String> required) {

        Map<String, Object> properties = new HashMap<>();
        for (var entry : stringProperties.entrySet()) {
            properties.put(entry.getKey(), Map.of(
                "type",        "string",
                "description", entry.getValue()
            ));
        }

        Map<String, Object> schema = new HashMap<>();
        schema.put("type",        "object");
        schema.put("description", description);
        schema.put("properties",  properties);
        schema.put("required",    required);
        return schema;
    }

    /** Convenience: schema with a single required string property. */
    public static Map<String, Object> singleStringParam(
            String paramName, String paramDescription) {
        return objectSchema(
            "Parameters",
            Map.of(paramName, paramDescription),
            List.of(paramName)
        );
    }
}
```

### 4.2 Tool definitions

```java
// src/main/java/com/example/mcp/ToolRegistry.java
package com.example.mcp;

import io.modelcontextprotocol.sdk.McpSchema;
import io.modelcontextprotocol.sdk.server.McpServerFeatures;

import java.util.List;
import java.util.Map;

/**
 * Registers all demo tools.
 * Each tool entry is a pair of (Tool descriptor, handler function).
 */
public final class ToolRegistry {

    private ToolRegistry() {}

    public static void register(McpServerFeatures.AsyncToolsRegistration reg) {
        // ── Tool 1: echo ──────────────────────────────────────────────────
        reg.addTool(
            new McpSchema.Tool(
                "echo",
                "Echoes the provided message back to the caller. Useful for smoke-testing.",
                SchemaBuilder.singleStringParam("message", "The text to echo back")
            ),
            request -> {
                String msg = (String) request.arguments().get("message");
                return McpSchema.CallToolResult.builder()
                    .content(List.of(McpSchema.TextContent.of("Echo: " + msg)))
                    .isError(false)
                    .build();
            }
        );

        // ── Tool 2: add ───────────────────────────────────────────────────
        reg.addTool(
            new McpSchema.Tool(
                "add",
                "Adds two integers and returns the sum.",
                SchemaBuilder.objectSchema(
                    "Two integer operands",
                    Map.of(
                        "a", "First integer operand",
                        "b", "Second integer operand"
                    ),
                    List.of("a", "b")
                )
            ),
            request -> {
                int a = Integer.parseInt(request.arguments().get("a").toString());
                int b = Integer.parseInt(request.arguments().get("b").toString());
                return McpSchema.CallToolResult.builder()
                    .content(List.of(McpSchema.TextContent.of(String.valueOf(a + b))))
                    .isError(false)
                    .build();
            }
        );

        // ── Tool 3: current_time ──────────────────────────────────────────
        reg.addTool(
            new McpSchema.Tool(
                "current_time",
                "Returns the current server date and time in ISO-8601 format.",
                Map.of("type", "object", "properties", Map.of())   // no parameters
            ),
            request -> {
                String now = java.time.Instant.now().toString();
                return McpSchema.CallToolResult.builder()
                    .content(List.of(McpSchema.TextContent.of(now)))
                    .isError(false)
                    .build();
            }
        );
    }
}
```

> **Error handling pattern:** If your tool throws, return
> `CallToolResult.builder().isError(true).content(List.of(TextContent.of(e.getMessage()))).build()`
> — the LLM sees the error message and can decide what to do next.

---

## 5. Implementing Resources

A **resource** is data the LLM (or user) can read by URI. Two flavours:

- **Static resource** — fixed URI, fixed or refreshed content.
- **Resource template** — URI template with `{param}` placeholders; the host fills them in.

```java
// src/main/java/com/example/mcp/ResourceRegistry.java
package com.example.mcp;

import io.modelcontextprotocol.sdk.McpSchema;
import io.modelcontextprotocol.sdk.server.McpServerFeatures;

import java.util.List;

public final class ResourceRegistry {

    private ResourceRegistry() {}

    public static void register(McpServerFeatures.AsyncResourcesRegistration reg) {

        // ── Static resource: server info ──────────────────────────────────
        reg.addResource(
            new McpSchema.Resource(
                "mcp://poc/info",           // URI — can be any scheme you choose
                "Server Info",
                "Basic information about this MCP PoC server",
                "text/plain",
                null                        // no annotations
            ),
            request -> McpSchema.ReadResourceResult.builder()
                .contents(List.of(
                    McpSchema.TextResourceContents.builder()
                        .uri("mcp://poc/info")
                        .mimeType("text/plain")
                        .text("""
                            MCP PoC Server
                            SDK : io.modelcontextprotocol.sdk
                            Java: %s
                            """.formatted(System.getProperty("java.version")))
                        .build()
                ))
                .build()
        );

        // ── Static resource: system properties (structured JSON) ──────────
        reg.addResource(
            new McpSchema.Resource(
                "mcp://poc/system-properties",
                "System Properties",
                "All JVM system properties as JSON",
                "application/json",
                null
            ),
            request -> {
                var props = System.getProperties();
                var sb = new StringBuilder("{");
                props.forEach((k, v) ->
                    sb.append("\"").append(k).append("\":\"")
                      .append(v.toString().replace("\"", "\\\""))
                      .append("\",")
                );
                if (sb.charAt(sb.length() - 1) == ',') sb.deleteCharAt(sb.length() - 1);
                sb.append("}");

                return McpSchema.ReadResourceResult.builder()
                    .contents(List.of(
                        McpSchema.TextResourceContents.builder()
                            .uri("mcp://poc/system-properties")
                            .mimeType("application/json")
                            .text(sb.toString())
                            .build()
                    ))
                    .build();
            }
        );

        // ── Resource template: echo by name ───────────────────────────────
        // URI template: mcp://poc/echo/{message}
        reg.addResourceTemplate(
            new McpSchema.ResourceTemplate(
                "mcp://poc/echo/{message}",
                "Echo Resource",
                "Returns the {message} portion of the URI as text content",
                "text/plain",
                null
            ),
            request -> {
                // The SDK resolves the template and gives you the full URI.
                // Extract the message segment manually.
                String uri  = request.uri();
                String text = uri.substring(uri.lastIndexOf('/') + 1);
                return McpSchema.ReadResourceResult.builder()
                    .contents(List.of(
                        McpSchema.TextResourceContents.builder()
                            .uri(uri)
                            .mimeType("text/plain")
                            .text("Resource echo: " + text)
                            .build()
                    ))
                    .build();
            }
        );
    }
}
```

---

## 6. Implementing Prompts

A **prompt** is a reusable message template the user (or LLM) can invoke by name, supplying arguments. The server returns a list of `PromptMessage` objects — the fully-rendered conversation fragment.

```java
// src/main/java/com/example/mcp/PromptRegistry.java
package com.example.mcp;

import io.modelcontextprotocol.sdk.McpSchema;
import io.modelcontextprotocol.sdk.server.McpServerFeatures;

import java.util.List;

public final class PromptRegistry {

    private PromptRegistry() {}

    public static void register(McpServerFeatures.AsyncPromptsRegistration reg) {

        // ── Prompt 1: code_review ─────────────────────────────────────────
        reg.addPrompt(
            new McpSchema.Prompt(
                "code_review",
                "Ask the LLM to review a code snippet",
                List.of(
                    new McpSchema.PromptArgument("language", "Programming language", true),
                    new McpSchema.PromptArgument("code",     "The code to review",   true)
                )
            ),
            request -> {
                String language = request.arguments().getOrDefault("language", "unknown");
                String code     = request.arguments().getOrDefault("code",     "");

                String systemText = "You are an expert " + language +
                    " developer. Review the following code for correctness, " +
                    "security, and style. Be concise.";

                String userText = "```" + language + "\n" + code + "\n```";

                return McpSchema.GetPromptResult.builder()
                    .description("Code review prompt for " + language)
                    .messages(List.of(
                        McpSchema.PromptMessage.builder()
                            .role(McpSchema.Role.USER)
                            .content(McpSchema.TextContent.of(systemText))
                            .build(),
                        McpSchema.PromptMessage.builder()
                            .role(McpSchema.Role.USER)
                            .content(McpSchema.TextContent.of(userText))
                            .build()
                    ))
                    .build();
            }
        );

        // ── Prompt 2: summarise ───────────────────────────────────────────
        reg.addPrompt(
            new McpSchema.Prompt(
                "summarise",
                "Summarise a block of text in a given number of bullet points",
                List.of(
                    new McpSchema.PromptArgument("text",   "Text to summarise", true),
                    new McpSchema.PromptArgument("points", "Number of bullet points (default 5)", false)
                )
            ),
            request -> {
                String text   = request.arguments().getOrDefault("text", "");
                String points = request.arguments().getOrDefault("points", "5");

                String userText = "Summarise the following text in exactly " + points +
                    " concise bullet points:\n\n" + text;

                return McpSchema.GetPromptResult.builder()
                    .description("Summarisation prompt")
                    .messages(List.of(
                        McpSchema.PromptMessage.builder()
                            .role(McpSchema.Role.USER)
                            .content(McpSchema.TextContent.of(userText))
                            .build()
                    ))
                    .build();
            }
        );
    }
}
```

---

## 7. Implementing Sampling

**Sampling** is the most advanced primitive: it lets the *server* ask the *host's LLM* to generate text as part of the server's own logic. The server sends a `CreateMessageRequest`; the host responds with a `CreateMessageResult`.

This is useful for agentic patterns where the server needs to do its own reasoning step.

```java
// src/main/java/com/example/mcp/SamplingDemo.java
package com.example.mcp;

import io.modelcontextprotocol.sdk.McpSchema;
import io.modelcontextprotocol.sdk.server.McpAsyncServerExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Demonstrates how a tool handler can use sampling to call back into the LLM.
 *
 * This class is NOT a registry — it provides a handler that is used
 * inside ToolRegistry when the "llm_expand" tool is invoked.
 */
public final class SamplingDemo {

    private SamplingDemo() {}

    /**
     * Tool handler for "llm_expand":
     * Takes a short phrase, asks the LLM to expand it into a paragraph,
     * then returns the result as the tool output.
     *
     * The McpAsyncServerExchange gives access to the sampling API.
     */
    public static Mono<McpSchema.CallToolResult> expand(
            McpSchema.CallToolRequest request,
            McpAsyncServerExchange exchange) {

        String phrase = (String) request.arguments().get("phrase");

        McpSchema.CreateMessageRequest samplingRequest = McpSchema.CreateMessageRequest.builder()
            .messages(List.of(
                McpSchema.SamplingMessage.builder()
                    .role(McpSchema.Role.USER)
                    .content(McpSchema.TextContent.of(
                        "Expand the following short phrase into one clear paragraph: " + phrase
                    ))
                    .build()
            ))
            .maxTokens(256)
            .systemPrompt("You are a helpful writing assistant.")
            .build();

        // exchange.createMessage() is non-blocking (Reactor Mono)
        return exchange.createMessage(samplingRequest)
            .map(result -> {
                String generated = ((McpSchema.TextContent) result.content()).text();
                return McpSchema.CallToolResult.builder()
                    .content(List.of(McpSchema.TextContent.of(generated)))
                    .isError(false)
                    .build();
            })
            .onErrorResume(e -> Mono.just(
                McpSchema.CallToolResult.builder()
                    .content(List.of(McpSchema.TextContent.of("Sampling error: " + e.getMessage())))
                    .isError(true)
                    .build()
            ));
    }
}
```

To wire this tool into `ToolRegistry`, add inside `register()`:

```java
// Inside ToolRegistry.register(), after the other tools:
reg.addToolWithExchange(
    new McpSchema.Tool(
        "llm_expand",
        "Asks the LLM to expand a short phrase into a full paragraph (uses sampling).",
        SchemaBuilder.singleStringParam("phrase", "The short phrase to expand")
    ),
    (request, exchange) -> SamplingDemo.expand(request, exchange)
);
```

> **Client support note:** The host must declare `sampling` capability during the MCP handshake
> for sampling calls to succeed. Claude Code and the official MCP inspector both support it.
> Cline and Bob may differ — check their release notes.

---

## 8. Transport 1 — stdio

**stdio** is the simplest transport. The host launches the server as a child process; JSON-RPC messages flow over `System.in` / `System.out`. No networking, no ports, no TLS — ideal for local dev tooling.

```java
// src/main/java/com/example/mcp/StdioServer.java
package com.example.mcp;

import io.modelcontextprotocol.sdk.McpSchema;
import io.modelcontextprotocol.sdk.server.McpAsyncServer;
import io.modelcontextprotocol.sdk.server.McpServer;
import io.modelcontextprotocol.sdk.server.McpServerFeatures;
import io.modelcontextprotocol.sdk.server.transport.StdioServerTransport;

/**
 * MCP server over stdio.
 * Run with: java -jar mcp-poc.jar
 * (or via the host's "command" config entry)
 */
public class StdioServer {

    public static void main(String[] args) throws Exception {

        // 1. Declare capabilities
        var capabilities = McpSchema.ServerCapabilities.builder()
            .tools(McpSchema.ServerCapabilities.ToolCapabilities.builder()
                .listChanged(true)
                .build())
            .resources(McpSchema.ServerCapabilities.ResourceCapabilities.builder()
                .subscribe(false)
                .listChanged(true)
                .build())
            .prompts(McpSchema.ServerCapabilities.PromptCapabilities.builder()
                .listChanged(false)
                .build())
            .sampling()   // enables sampling (server can call back into the LLM)
            .build();

        // 2. Build feature registrations
        var features = McpServerFeatures.async()
            .serverInfo(new McpSchema.Implementation("mcp-poc-stdio", "1.0.0"))
            .capabilities(capabilities)
            .tools(ToolRegistry::register)
            .resources(ResourceRegistry::register)
            .prompts(PromptRegistry::register)
            .build();

        // 3. Create the async server
        McpAsyncServer server = McpServer.async(features);

        // 4. Connect via stdio transport (blocks until the host closes the pipe)
        var transport = new StdioServerTransport();
        server.connect(transport).block();
    }
}
```

**Important:** Do **not** write anything to `System.out` outside the SDK (e.g. no `System.out.println`). The host interprets every byte on stdout as a JSON-RPC message. Use SLF4J to a file or `System.err` for debug output.

Configure `logback.xml` accordingly:

```xml
<!-- src/main/resources/logback.xml -->
<configuration>
    <appender name="STDERR" class="ch.qos.logback.core.ConsoleAppender">
        <target>System.err</target>
        <encoder>
            <pattern>%d{HH:mm:ss} %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    <root level="INFO">
        <appender-ref ref="STDERR"/>
    </root>
</configuration>
```

---

## 9. Transport 2 — HTTP + SSE (legacy, spec 2024-11-05)

The legacy HTTP transport uses two endpoints:
- `POST /sse` — client opens a Server-Sent Events stream; server pushes events here.
- `POST /message` — client sends JSON-RPC requests here.

This is the transport used by most existing MCP clients before the 2025-03-26 spec.

```java
// src/main/java/com/example/mcp/SseHttpServer.java
package com.example.mcp;

import io.modelcontextprotocol.sdk.McpSchema;
import io.modelcontextprotocol.sdk.server.McpAsyncServer;
import io.modelcontextprotocol.sdk.server.McpServer;
import io.modelcontextprotocol.sdk.server.McpServerFeatures;
import io.modelcontextprotocol.sdk.server.transport.WebFluxSseServerTransport;
import org.springframework.web.reactive.function.server.RouterFunctions;
import reactor.netty.http.server.HttpServer;

/**
 * MCP server over HTTP + SSE (legacy transport, spec 2024-11-05).
 *
 * Starts a Reactor Netty HTTP server on localhost:8080.
 * Endpoints:
 *   GET  http://localhost:8080/sse      ← SSE event stream
 *   POST http://localhost:8080/message  ← JSON-RPC messages
 */
public class SseHttpServer {

    public static void main(String[] args) throws Exception {

        var capabilities = McpSchema.ServerCapabilities.builder()
            .tools(McpSchema.ServerCapabilities.ToolCapabilities.builder()
                .listChanged(true).build())
            .resources(McpSchema.ServerCapabilities.ResourceCapabilities.builder()
                .subscribe(false).listChanged(true).build())
            .prompts(McpSchema.ServerCapabilities.PromptCapabilities.builder()
                .listChanged(false).build())
            .sampling()
            .build();

        var features = McpServerFeatures.async()
            .serverInfo(new McpSchema.Implementation("mcp-poc-sse", "1.0.0"))
            .capabilities(capabilities)
            .tools(ToolRegistry::register)
            .resources(ResourceRegistry::register)
            .prompts(PromptRegistry::register)
            .build();

        McpAsyncServer server = McpServer.async(features);

        // SSE transport — base URL tells the client where to POST messages
        var transport = new WebFluxSseServerTransport(
            "/message"    // the path clients should POST to
        );

        // Wire the transport into a Reactor Netty HTTP server
        var routerFunction = transport.getRouterFunction();
        var httpHandler    = RouterFunctions.toHttpHandler(routerFunction);
        var adapter        = new org.springframework.http.server.reactive
                                .ReactorHttpHandlerAdapter(httpHandler);

        HttpServer.create()
            .host("127.0.0.1")   // security: never bind to 0.0.0.0
            .port(8080)
            .handle(adapter)
            .bindNow();

        server.connect(transport).block();
    }
}
```

Client config for this transport (`settings.json` or `.bob/mcp.json`):

```json
"mcp.servers": {
  "mcp-poc-sse": {
    "type": "sse",
    "url": "http://localhost:8080/sse"
  }
}
```

---

## 10. Transport 3 — Streamable HTTP (spec 2025-03-26)

Streamable HTTP replaces SSE with a single `POST /mcp` endpoint. Responses can be a plain JSON body (for simple request/response) or an SSE stream (for streaming). This is the current recommended transport for HTTP-based servers.

```java
// src/main/java/com/example/mcp/StreamableHttpServer.java
package com.example.mcp;

import io.modelcontextprotocol.sdk.McpSchema;
import io.modelcontextprotocol.sdk.server.McpAsyncServer;
import io.modelcontextprotocol.sdk.server.McpServer;
import io.modelcontextprotocol.sdk.server.McpServerFeatures;
import io.modelcontextprotocol.sdk.server.transport.WebFluxStreamableHttpServerTransport;
import org.springframework.web.reactive.function.server.RouterFunctions;
import reactor.netty.http.server.HttpServer;

/**
 * MCP server over Streamable HTTP (spec 2025-03-26).
 *
 * Single endpoint: POST http://localhost:8081/mcp
 */
public class StreamableHttpServer {

    public static void main(String[] args) throws Exception {

        var capabilities = McpSchema.ServerCapabilities.builder()
            .tools(McpSchema.ServerCapabilities.ToolCapabilities.builder()
                .listChanged(true).build())
            .resources(McpSchema.ServerCapabilities.ResourceCapabilities.builder()
                .subscribe(false).listChanged(true).build())
            .prompts(McpSchema.ServerCapabilities.PromptCapabilities.builder()
                .listChanged(false).build())
            .sampling()
            .build();

        var features = McpServerFeatures.async()
            .serverInfo(new McpSchema.Implementation("mcp-poc-streamable", "1.0.0"))
            .capabilities(capabilities)
            .tools(ToolRegistry::register)
            .resources(ResourceRegistry::register)
            .prompts(PromptRegistry::register)
            .build();

        McpAsyncServer server = McpServer.async(features);

        // Streamable HTTP transport — single /mcp endpoint
        var transport = new WebFluxStreamableHttpServerTransport("/mcp");

        var routerFunction = transport.getRouterFunction();
        var httpHandler    = RouterFunctions.toHttpHandler(routerFunction);
        var adapter        = new org.springframework.http.server.reactive
                                .ReactorHttpHandlerAdapter(httpHandler);

        HttpServer.create()
            .host("127.0.0.1")   // security: never bind to 0.0.0.0
            .port(8081)
            .handle(adapter)
            .bindNow();

        server.connect(transport).block();
    }
}
```

Client config:

```json
"mcp.servers": {
  "mcp-poc-streamable": {
    "type": "streamable-http",
    "url": "http://localhost:8081/mcp"
  }
}
```

---

## 11. Registering the Server with a Client

### Claude Code (`~/.claude/claude_desktop_config.json`)

```json
{
  "mcpServers": {
    "mcp-poc": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/mcp-poc-1.0-SNAPSHOT.jar"]
    }
  }
}
```

### Cline (VS Code `settings.json`)

```json
"cline.mcpServers": {
  "mcp-poc": {
    "command": "java",
    "args": ["-jar", "C:/path/to/mcp-poc-1.0-SNAPSHOT.jar"],
    "env": {}
  }
}
```

### Bob (`.bob/mcp.json` in workspace, or global settings)

```json
{
  "servers": {
    "mcp-poc": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "C:/path/to/mcp-poc-1.0-SNAPSHOT.jar"]
    }
  }
}
```

All three examples assume the **stdio** transport (fat-JAR as child process). For HTTP, swap the entry to the appropriate `type: "sse"` or `type: "streamable-http"` form shown in sections 9 and 10.

---

## 12. Testing and Inspection — MCP Inspector vs Java-native

### Build first

```bash
mvn clean package -q
# Produces: target/mcp-poc-1.0-SNAPSHOT.jar
```

### 12.1 MCP Inspector — current state of the art (still Node-based)

**Short answer:** Yes, the MCP Inspector is still the official, reference-level debugging UI as of mid-2025.
There is **no Java-native graphical equivalent**. The ecosystem has not produced one, and the MCP org has not signalled plans to.

What has changed since its launch:
- The preferred invocation is now `npx` (no global install, always gets the latest version):

```bash
# stdio server — Inspector launches the JAR as a child process
npx @modelcontextprotocol/inspector java -jar target/mcp-poc-1.0-SNAPSHOT.jar

# HTTP+SSE server (start SseHttpServer first)
npx @modelcontextprotocol/inspector --transport sse --url http://localhost:8080/sse

# Streamable HTTP server
npx @modelcontextprotocol/inspector --transport streamable-http --url http://localhost:8081/mcp
```

- The UI now has full support for all three transports, displays raw JSON-RPC message traffic, and
  can exercise sampling (if the server declares the capability).
- If you have Node ≥ 18 installed **purely for this tool**, it imposes no Java dependency on your
  server itself — the Inspector is an external dev-time tool, not a runtime dependency.

**The pragmatic position:** If you have Node on the machine (even only for this), the Inspector
gives you the fastest interactive feedback loop. The underlying ugliness of JavaScript is entirely
hidden behind the browser UI.

---

### 12.2 Java-native option A — write a `McpClient` smoke-test class

The same `io.modelcontextprotocol.sdk:mcp` artifact you already have in the POM ships a full
**client** API (`McpClient`, `McpAsyncClient`). You can write a plain `main()` class that:

1. Starts your server in-process (or as a child process).
2. Connects to it as a client.
3. Calls every tool, reads every resource, invokes every prompt, and prints the results.

No Node, no browser, no external dependency.

```java
// src/test/java/com/example/mcp/JavaInspector.java
package com.example.mcp;

import io.modelcontextprotocol.sdk.McpSchema;
import io.modelcontextprotocol.sdk.client.McpAsyncClient;
import io.modelcontextprotocol.sdk.client.McpClient;
import io.modelcontextprotocol.sdk.client.McpClientFeatures;
import io.modelcontextprotocol.sdk.client.transport.StdioClientTransport;

import java.util.List;
import java.util.Map;

/**
 * Pure-Java MCP inspector: connects to the stdio server as a client,
 * then interrogates every declared capability and prints the results.
 *
 * Run with: mvn test-compile exec:java -Dexec.mainClass=com.example.mcp.JavaInspector
 *           (or as a regular JUnit 5 test — see section 12.3)
 */
public class JavaInspector {

    public static void main(String[] args) throws Exception {

        // 1. Launch the server as a child process via stdio transport
        var transport = StdioClientTransport.builder()
            .command(List.of("java", "-jar", "target/mcp-poc-1.0-SNAPSHOT.jar"))
            .build();

        // 2. Build client with sampling capability declared
        //    (so the server's llm_expand tool can call back — we mock the response)
        var features = McpClientFeatures.async()
            .clientInfo(new McpSchema.Implementation("java-inspector", "1.0.0"))
            .capabilities(McpSchema.ClientCapabilities.builder()
                .sampling()   // declare we support sampling requests from the server
                .build())
            // Mock sampling handler: just echoes the request back as the "LLM response"
            .sampling(req -> {
                String userText = ((McpSchema.TextContent) req.messages().getLast().content()).text();
                return io.reactor.core.publisher.Mono.just(
                    McpSchema.CreateMessageResult.builder()
                        .role(McpSchema.Role.ASSISTANT)
                        .content(McpSchema.TextContent.of("[mock LLM] " + userText))
                        .model("mock-model")
                        .stopReason("endTurn")
                        .build()
                );
            })
            .build();

        McpAsyncClient client = McpClient.async(features);
        client.connect(transport).block();

        System.out.println("=== MCP Java Inspector ===\n");

        // 3. List and call tools
        var tools = client.listTools().block().tools();
        System.out.println("── Tools (" + tools.size() + ") ──────────────────────");
        for (McpSchema.Tool tool : tools) {
            System.out.println("  " + tool.name() + " : " + tool.description());
        }

        // Call echo
        var echoResult = client.callTool(
            McpSchema.CallToolRequest.builder()
                .name("echo")
                .arguments(Map.of("message", "hello from JavaInspector"))
                .build()
        ).block();
        System.out.println("\necho result  → " + ((McpSchema.TextContent) echoResult.content().getFirst()).text());

        // Call add
        var addResult = client.callTool(
            McpSchema.CallToolRequest.builder()
                .name("add")
                .arguments(Map.of("a", "21", "b", "21"))
                .build()
        ).block();
        System.out.println("add result   → " + ((McpSchema.TextContent) addResult.content().getFirst()).text());

        // Call current_time
        var timeResult = client.callTool(
            McpSchema.CallToolRequest.builder().name("current_time").arguments(Map.of()).build()
        ).block();
        System.out.println("time result  → " + ((McpSchema.TextContent) timeResult.content().getFirst()).text());

        // 4. List and read resources
        var resources = client.listResources().block().resources();
        System.out.println("\n── Resources (" + resources.size() + ") ─────────────────────");
        for (McpSchema.Resource r : resources) {
            var contents = client.readResource(
                McpSchema.ReadResourceRequest.builder().uri(r.uri()).build()
            ).block().contents();
            String preview = ((McpSchema.TextResourceContents) contents.getFirst()).text();
            if (preview.length() > 80) preview = preview.substring(0, 80) + "…";
            System.out.println("  " + r.uri() + "\n    → " + preview.replace("\n", " "));
        }

        // Read a resource template instance
        var tmplResult = client.readResource(
            McpSchema.ReadResourceRequest.builder()
                .uri("mcp://poc/echo/hello-from-java")
                .build()
        ).block().contents();
        System.out.println("  template    → " +
            ((McpSchema.TextResourceContents) tmplResult.getFirst()).text());

        // 5. List and invoke prompts
        var prompts = client.listPrompts().block().prompts();
        System.out.println("\n── Prompts (" + prompts.size() + ") ──────────────────────");
        for (McpSchema.Prompt p : prompts) {
            System.out.println("  " + p.name() + " : " + p.description());
        }

        var promptResult = client.getPrompt(
            McpSchema.GetPromptRequest.builder()
                .name("summarise")
                .arguments(Map.of("text", "MCP is a protocol. Java is great. Testing is important.", "points", "3"))
                .build()
        ).block();
        System.out.println("\nsummarise prompt messages:");
        for (McpSchema.PromptMessage m : promptResult.messages()) {
            System.out.println("  [" + m.role() + "] " +
                ((McpSchema.TextContent) m.content()).text());
        }

        // 6. Exercise sampling via the llm_expand tool
        System.out.println("\n── Sampling (via llm_expand tool) ──────");
        var samplingResult = client.callTool(
            McpSchema.CallToolRequest.builder()
                .name("llm_expand")
                .arguments(Map.of("phrase", "MCP enables LLM tool use"))
                .build()
        ).block();
        System.out.println("  → " + ((McpSchema.TextContent) samplingResult.content().getFirst()).text());

        client.close().block();
        System.out.println("\nDone.");
    }
}
```

This class exercises **every primitive** the server exposes and prints results to stdout.
No Node, no browser, no external tooling.

---

### 12.3 Java-native option B — JUnit 5 integration test (in-process)

For automated regression testing, wire server and client together **inside the same JVM** using
a `Sinks`-based in-memory transport. This is fast, deterministic, and CI-friendly.

Add JUnit 5 to the POM's `<dependencies>`:

```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.11.0</version>
    <scope>test</scope>
</dependency>
```

And add the Surefire plugin to `<build><plugins>`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>3.3.1</version>
</plugin>
```

Then write the integration test:

```java
// src/test/java/com/example/mcp/McpServerIntegrationTest.java
package com.example.mcp;

import io.modelcontextprotocol.sdk.McpSchema;
import io.modelcontextprotocol.sdk.client.McpAsyncClient;
import io.modelcontextprotocol.sdk.client.McpClient;
import io.modelcontextprotocol.sdk.client.McpClientFeatures;
import io.modelcontextprotocol.sdk.server.McpAsyncServer;
import io.modelcontextprotocol.sdk.server.McpServer;
import io.modelcontextprotocol.sdk.server.McpServerFeatures;
import io.modelcontextprotocol.sdk.transport.InMemoryMcpTransport;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In-process integration test: server and client share an InMemoryMcpTransport.
 * No child processes, no ports, no Node.
 */
class McpServerIntegrationTest {

    private McpAsyncServer server;
    private McpAsyncClient client;

    @BeforeEach
    void setUp() {
        // Build server (same config as StdioServer)
        var capabilities = McpSchema.ServerCapabilities.builder()
            .tools(McpSchema.ServerCapabilities.ToolCapabilities.builder()
                .listChanged(false).build())
            .resources(McpSchema.ServerCapabilities.ResourceCapabilities.builder()
                .subscribe(false).listChanged(false).build())
            .prompts(McpSchema.ServerCapabilities.PromptCapabilities.builder()
                .listChanged(false).build())
            .build();

        var serverFeatures = McpServerFeatures.async()
            .serverInfo(new McpSchema.Implementation("mcp-poc-test", "1.0.0"))
            .capabilities(capabilities)
            .tools(ToolRegistry::register)
            .resources(ResourceRegistry::register)
            .prompts(PromptRegistry::register)
            .build();

        server = McpServer.async(serverFeatures);

        // In-memory paired transport — no network, no process
        var transport = InMemoryMcpTransport.createServerClientPair();

        var clientFeatures = McpClientFeatures.async()
            .clientInfo(new McpSchema.Implementation("test-client", "1.0.0"))
            .build();

        client = McpClient.async(clientFeatures);
        client.connect(transport.client()).block();
        server.connect(transport.server()).block();
    }

    @AfterEach
    void tearDown() {
        client.close().block();
        server.close().block();
    }

    // ── Tools ────────────────────────────────────────────────────────────

    @Test
    void echoToolReturnsPrefixedMessage() {
        var result = client.callTool(
            McpSchema.CallToolRequest.builder()
                .name("echo")
                .arguments(Map.of("message", "world"))
                .build()
        ).block();

        assertFalse(result.isError());
        var text = ((McpSchema.TextContent) result.content().getFirst()).text();
        assertEquals("Echo: world", text);
    }

    @Test
    void addToolReturnsSumAsString() {
        var result = client.callTool(
            McpSchema.CallToolRequest.builder()
                .name("add")
                .arguments(Map.of("a", "17", "b", "25"))
                .build()
        ).block();

        assertFalse(result.isError());
        assertEquals("42", ((McpSchema.TextContent) result.content().getFirst()).text());
    }

    @Test
    void currentTimeToolReturnsIso8601() {
        var result = client.callTool(
            McpSchema.CallToolRequest.builder().name("current_time").arguments(Map.of()).build()
        ).block();

        assertFalse(result.isError());
        String time = ((McpSchema.TextContent) result.content().getFirst()).text();
        // Rough ISO-8601 check
        assertTrue(time.contains("T") && time.endsWith("Z"), "Expected ISO-8601, got: " + time);
    }

    // ── Resources ────────────────────────────────────────────────────────

    @Test
    void infoResourceContainsServerName() {
        var result = client.readResource(
            McpSchema.ReadResourceRequest.builder().uri("mcp://poc/info").build()
        ).block();

        String text = ((McpSchema.TextResourceContents) result.contents().getFirst()).text();
        assertTrue(text.contains("MCP PoC Server"));
    }

    @Test
    void echoTemplateReflectsUriSegment() {
        var result = client.readResource(
            McpSchema.ReadResourceRequest.builder().uri("mcp://poc/echo/junit-test").build()
        ).block();

        String text = ((McpSchema.TextResourceContents) result.contents().getFirst()).text();
        assertEquals("Resource echo: junit-test", text);
    }

    // ── Prompts ──────────────────────────────────────────────────────────

    @Test
    void summarisePromptContainsText() {
        var result = client.getPrompt(
            McpSchema.GetPromptRequest.builder()
                .name("summarise")
                .arguments(Map.of("text", "Alpha beta gamma."))
                .build()
        ).block();

        String msg = ((McpSchema.TextContent) result.messages().getFirst().content()).text();
        assertTrue(msg.contains("Alpha beta gamma."));
    }

    @Test
    void toolListContainsExpectedTools() {
        var tools = client.listTools().block().tools();
        var names = tools.stream().map(McpSchema.Tool::name).toList();
        assertTrue(names.contains("echo"));
        assertTrue(names.contains("add"));
        assertTrue(names.contains("current_time"));
    }
}
```

Run with:

```bash
mvn test
```

---

### 12.4 Comparison: which approach to use when

| Situation | Best approach |
|---|---|
| First-time interactive exploration of a server | MCP Inspector (`npx`) |
| CI/CD automated regression tests | JUnit 5 in-process (option B) |
| Ad-hoc Java smoke test without Node | `JavaInspector` main class (option A) |
| Debugging raw JSON-RPC message flow | MCP Inspector (shows full protocol traffic) |
| Testing HTTP transports end-to-end | MCP Inspector or `JavaInspector` with `HttpClientTransport` |

**Bottom line:** There is no Java-native MCP Inspector equivalent that matches the interactive
UI experience. For automated testing the JUnit in-process approach is actually *superior* to
the Inspector (faster, deterministic, no Node required). For interactive exploration, the Inspector
remains the only option — but `npx` keeps it ephemeral and non-invasive.

---

## 13. Sync vs Async API, Env-var Config, listChanged Notifications

### 13.1 Synchronous vs Reactive server API

The SDK ships two server flavours that share the same feature registration API:

| Class | Execution model | When to use |
|---|---|---|
| `McpSyncServer` | Blocking — handlers return values directly | Simple tools, no reactive libraries in the project |
| `McpAsyncServer` | Non-blocking — handlers return `Mono<T>` | Long-running tools, HTTP transport, existing reactive codebase |

The tutorial uses `McpAsyncServer` throughout because it is the more general choice.
If you find Project Reactor unfamiliar, here is the same `echo` tool rewritten for the sync API:

```java
// Sync server setup
var syncFeatures = McpServerFeatures.sync()
    .serverInfo(new McpSchema.Implementation("mcp-poc-sync", "1.0.0"))
    .capabilities(capabilities)
    .tools(reg -> reg.addTool(
        new McpSchema.Tool("echo", "Echo the message", SchemaBuilder.singleStringParam("message", "Text")),
        request -> {                                                // returns CallToolResult directly
            String msg = (String) request.arguments().get("message");
            return McpSchema.CallToolResult.builder()
                .content(List.of(McpSchema.TextContent.of("Echo: " + msg)))
                .isError(false)
                .build();
        }
    ))
    .build();

McpSyncServer syncServer = McpServer.sync(syncFeatures);
syncServer.connect(new StdioServerTransport());   // blocks — no .block() needed
```

The sync API is simpler to read but does not support the HTTP transports (those require reactive).
For a pure-stdio server doing simple local operations, sync is perfectly adequate.

---

### 13.2 Passing configuration via environment variables

Real MCP tools typically need credentials or config (API keys, database URLs, file paths).
The correct pattern is environment variables — **never hardcode secrets** in the JAR.

In your tool handler, read them at call time (not at startup), so the server starts cleanly
even if the variable is temporarily absent:

```java
// Inside a tool handler — read env var defensively at call time
reg.addTool(
    new McpSchema.Tool(
        "fetch_weather",
        "Fetches current weather for a city using an external API.",
        SchemaBuilder.singleStringParam("city", "City name")
    ),
    request -> {
        String apiKey = System.getenv("WEATHER_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return McpSchema.CallToolResult.builder()
                .content(List.of(McpSchema.TextContent.of(
                    "Configuration error: WEATHER_API_KEY environment variable is not set.")))
                .isError(true)
                .build();
        }
        String city = (String) request.arguments().get("city");
        // ... call the weather API using apiKey and city ...
        return McpSchema.CallToolResult.builder()
            .content(List.of(McpSchema.TextContent.of("Weather for " + city + ": sunny")))
            .isError(false)
            .build();
    }
);
```

Inject env vars in the host config so the variable is scoped to the server process:

```json
// Claude Code / Cline / Bob stdio config with env injection
{
  "command": "java",
  "args": ["-jar", "/path/to/mcp-poc.jar"],
  "env": {
    "WEATHER_API_KEY": "your-key-here"
  }
}
```

> **Security note:** The `env` block in host config files is stored in plain text.
> For production workloads use a secrets manager (HashiCorp Vault, IBM Key Protect)
> and have the tool fetch the secret at call time rather than receiving it as a static env var.

---

### 13.3 Pushing `listChanged` notifications at runtime

When you declare `listChanged: true` in `ServerCapabilities`, your server can **push a notification
to the client** whenever the tool or resource list changes (e.g. a new integration becomes
available, or a tool is disabled). The client will re-call `tools/list` automatically.

```java
// Assuming you hold a reference to your McpAsyncServer
McpAsyncServer server = McpServer.async(features);

// ... later, when the list changes (e.g. triggered by a config reload) ...

// Notify all connected clients that the tool list has changed
server.notifyToolsListChanged().block();

// Notify that the resource list has changed
server.notifyResourcesListChanged().block();
```

This is useful when building a server that dynamically loads plugins, connects to a database whose
schema changes, or gates tools on feature flags. The client (Claude, Cline, Bob) will
automatically re-query and update its tool palette without requiring a server restart.

---

## 14. Key Takeaways and Next Steps

### What the PoC demonstrates

| Feature | Covered in |
|---|---|
| Tool with string input | `ToolRegistry` — `echo` |
| Tool with multiple inputs | `ToolRegistry` — `add` |
| Tool with no inputs | `ToolRegistry` — `current_time` |
| Static text resource | `ResourceRegistry` — `mcp://poc/info` |
| Static JSON resource | `ResourceRegistry` — `mcp://poc/system-properties` |
| Resource template | `ResourceRegistry` — `mcp://poc/echo/{message}` |
| Multi-message prompt | `PromptRegistry` — `code_review` |
| Prompt with optional arg | `PromptRegistry` — `summarise` |
| Sampling callback | `SamplingDemo` — `llm_expand` tool |
| stdio transport | `StdioServer` |
| HTTP + SSE transport | `SseHttpServer` |
| Streamable HTTP transport | `StreamableHttpServer` |
| Java-native smoke test | `JavaInspector` main class |
| In-process JUnit 5 tests | `McpServerIntegrationTest` |
| Sync server variant | Section 13.1 |
| Env-var config pattern | Section 13.2 |
| Dynamic listChanged push | Section 13.3 |

### Recommended next steps

1. **Add a real tool** — e.g. a tool that reads a local file, queries a REST API, or runs a database query. Follow the env-var pattern in section 13.2 for any credentials.
2. **Dynamic resources** — implement `resources/subscribe` so the server can push update notifications when resource data changes (complementary to `listChanged`).
3. **Progress notifications** — for long-running tools, call `exchange.sendProgress(...)` to stream intermediate results to the client.
4. **Authentication** — for HTTP transports in non-local deployments, add a Bearer token check in a Reactor Netty filter before the MCP router.
5. **Spring AI MCP Boot Starter** — once you've mastered the raw SDK, the Spring AI starter reduces all of the above to `@Tool`, `@Resource`, and `@Prompt` annotations on Spring beans.

### Official references

| Resource | URL |
|---|---|
| MCP specification | https://spec.modelcontextprotocol.io |
| Java SDK source + examples | https://github.com/modelcontextprotocol/java-sdk |
| Maven Central | https://central.sonatype.com/artifact/io.modelcontextprotocol.sdk/mcp |
| MCP Inspector | https://github.com/modelcontextprotocol/inspector |
| Spring AI MCP docs | https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html |
