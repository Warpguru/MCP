# AI Assistant Onboarding & Project Memory: AGENTS.md

Welcome! This document provides essential project context, architectural decisions, package structure secrets, and core lessons learned from implementing the official **Model Context Protocol (MCP) Java SDK** (specifically version `2.0.1` / `mcp-spring-webflux:0.18.4`) in this workspace.

---

## 1. Project Purpose & Layout

This project is a hands-on tutorial and implementation testbed for the Java MCP SDK. It consists of:
* **The Launcher (`edu.java.MCP`)**: A central CLI entrypoint that acts as a router.
  * Running `java -jar target/MCP-1.0.0.jar server` starts the MCP Server.
  * Running `java -jar target/MCP-1.0.0.jar client` starts the MCP Client (Feature Dumper).
* **The MCP Server (`edu.java.service.Server`)**: A compliant server implementing Tools, Resources, Prompts, and Sampling.
* **The MCP Client (`edu.java.service.Client`)**: A universal diagnostics client that launches any target server as a child process via standard I/O, queries its capabilities, dumps its definitions, and executes demo flows.

---

## 2. MCP Java SDK 2.0.1 Architectural Secrets & Real Signatures

Preliminary draft documents or documentation of different versions contain outdated namespace paths and API usages. Use the actual SDK 2.0.1 APIs detailed below to avoid compiler errors.

### 2.1 Actual Namespace Paths (No `.sdk` sub-package)
The actual classes inside `mcp-core-2.0.1.jar` are packaged directly under `.spec` or root namespaces, **not** under `.sdk`:
* **Incorrect**: `io.modelcontextprotocol.sdk.McpSchema`
* **Correct**: `io.modelcontextprotocol.spec.McpSchema`
* **Models**: `io.modelcontextprotocol.spec.McpSchema.Tool`, `io.modelcontextprotocol.spec.McpSchema.CallToolRequest`, `io.modelcontextprotocol.spec.McpSchema.CallToolResult`, `io.modelcontextprotocol.spec.McpSchema.TextContent`, `io.modelcontextprotocol.spec.McpSchema.JsonSchema`.
* **Server**: `io.modelcontextprotocol.server.McpServer` and `io.modelcontextprotocol.server.McpAsyncServer`.
* **Client**: `io.modelcontextprotocol.client.McpClient` and `io.modelcontextprotocol.client.McpAsyncClient`.
* **JSON Mapper**: `io.modelcontextprotocol.json.McpJsonDefaults` (provides `McpJsonDefaults.getMapper()` for the default `McpJsonMapper` instance).

### 2.2 Model Classes are Java Records — Always Use Builders (Constructors Deprecated)
Core payload models are Java **records**. All short-form constructors are `@Deprecated` in `2.0.1`. Always use the static builder factories:

| Deprecated constructor | Current builder replacement |
|---|---|
| `new McpSchema.Implementation(name, version)` | `McpSchema.Implementation.builder(name, version).build()` |
| `new CallToolRequest(name, Map)` | `CallToolRequest.builder(name).arguments(map).build()` |
| `new GetPromptRequest(name, Map)` | `GetPromptRequest.builder(name).arguments(map).build()` |
| `new ReadResourceRequest(uri)` | `ReadResourceRequest.builder(uri).build()` |
| `new TextContent(String text)` | `TextContent.builder(text).build()` |
| `new GetPromptResult(description, List)` | `GetPromptResult.builder(messageList).description(desc).build()` |
| `new Prompt(name, description, List)` | `Prompt.builder(name).description(desc).arguments(list).build()` |
| `new PromptArgument(name, description, Boolean)` | `PromptArgument.builder(name).description(desc).required(bool).build()` |
| `new ResourceTemplate(uriTemplate, name, desc, mime, null)` | `ResourceTemplate.builder(uriTemplate, name).description(desc).mimeType(mime).build()` |

Other builders (unchanged, no deprecated constructor):
* **`CallToolResult`**: `CallToolResult.builder().content(list).isError(bool).build()`
* **`Tool`**: `Tool.builder(name).description(desc).inputSchema(schemaMap).build()` — `inputSchema` is a `Map<String, Object>`, **not** a JSON string.
* **`Resource`**: `Resource.builder(uri, name).description(desc).mimeType(type).build()`

### 2.3 Tool Schemas are Maps — No Jackson Serialization Required
Since `Tool.inputSchema` is now a `Map<String, Object>` (not a String), pass the schema `Map` directly from `SchemaBuilder`:
```java
Tool tool = Tool.builder("my_tool")
    .description("Does something useful")
    .inputSchema(SchemaBuilder.singleStringParameter("param", "A parameter"))
    .build();
```
The `toJson()` helper and `ObjectMapper` field in `Server.java` are now unused but retained for backward compatibility with any external callers.

### 2.4 Tool Handler Signature Changed — `CallToolRequest` Instead of `Map`
In `0.11.0` the `AsyncToolSpecification` BiFunction received `Map<String, Object>` as the second argument. In `2.0.1` it receives `McpSchema.CallToolRequest`. Access arguments via `.arguments()`:
```java
return new AsyncToolSpecification(tool, (exchange, callToolRequest) -> {
    String value = (String) callToolRequest.arguments().get("paramName");
    return Mono.just(CallToolResult.builder()
        .content(List.of(new TextContent("Result: " + value)))
        .isError(false).build());
});
```

### 2.5 Resource Templates Require a Handler — `AsyncResourceTemplateSpecification`
In `0.11.0`, `.resourceTemplates(ResourceTemplate...)` on the server builder accepted raw `ResourceTemplate` objects. In `2.0.1`, it requires `McpServerFeatures.AsyncResourceTemplateSpecification` objects that bundle both the template metadata and a read handler:
```java
ResourceTemplate rt = ResourceTemplate.builder("mcp://poc/echo/{message}", "Echo")
    .description("Description").mimeType("text/plain").build();
AsyncResourceTemplateSpecification spec = new AsyncResourceTemplateSpecification(rt, (exchange, request) -> {
    String text = request.uri().substring(request.uri().lastIndexOf('/') + 1);
    return Mono.just(new ReadResourceResult(List.of(new TextResourceContents(request.uri(), "text/plain", text))));
});
```

### 2.6 Transport Classes Now Require `McpJsonMapper`
All transport constructors and builders that previously accepted a Jackson `ObjectMapper` now require `McpJsonMapper`. Use `McpJsonDefaults.getMapper()` for the default mapper:
```java
// Server transports
new StdioServerTransportProvider(McpJsonDefaults.getMapper())
WebFluxSseServerTransportProvider.builder().jsonMapper(McpJsonDefaults.getMapper()).sseEndpoint(SSE_ENDPOINT).messageEndpoint(MESSAGE_ENDPOINT).build()
WebFluxStreamableServerTransportProvider.builder().jsonMapper(McpJsonDefaults.getMapper()).messageEndpoint(STREAMABLE_ENDPOINT).build()

// Client transports
new StdioClientTransport(params, McpJsonDefaults.getMapper())
HttpClientSseClientTransport.builder(sseUrl).build()   // NOTE: entire class is @Deprecated in 2.0.1; SSE is superseded by Streamable HTTP
HttpClientStreamableHttpTransport.builder(baseUrl).endpoint(path).build()  // unchanged
```

### 2.7 Ultra-Simplified Server Capabilities Builders (Unchanged)
Setting capabilities on `ServerCapabilities` in `2.0.1` remains the same:
```java
ServerCapabilities capabilities = ServerCapabilities.builder()
    .tools(true)                  // Declares tools support
    .prompts(true)                // Declares prompts support
    .resources(false, true)       // Declares resources support: resources(subscribe, listChanged)
    .build();
```

### 2.8 `CreateMessageResult.builder()` — Use the Static Factory Overload
The no-arg `CreateMessageResult.builder()` constructor is **package-private** in `2.0.1`. Use the static factory overload instead:
```java
// Correct in 2.0.1:
CreateMessageResult.builder(Role.ASSISTANT, TextContent.builder("reply").build(), "mock-model")
    .stopReason(StopReason.END_TURN).build()

// Wrong (package-private constructor, will not compile from user code):
CreateMessageResult.builder().role(Role.ASSISTANT)...
```

---

## 3. Server-Client Connection Patterns

### 3.1 The MCP Server (Async Specification Pattern)
When starting the server, instantiate `McpServer.async(transportProvider)` and chain the tool, resource, and prompt registrations directly onto the fluent builder before calling `.build()`:
```java
McpAsyncServer server = McpServer.async(new StdioServerTransportProvider(McpJsonDefaults.getMapper()))
    .serverInfo("my-server", "1.0.0")
    .capabilities(capabilities)
    .tools(toolSpec)
    .resources(resourceSpec)
    .resourceTemplates(resourceTemplateSpec)  // AsyncResourceTemplateSpecification, not ResourceTemplate
    .prompts(promptSpec)
    .build();
```

To keep a Stdio Server alive, **never** read from `System.in` as it competes with the transport. Keep the main thread running indefinitely via:
```java
Mono.never().block();
```

### 3.2 The MCP Client (Initialization Pattern)
Build the `McpAsyncClient` with the client parameters and connect/handshake by invoking `.initialize().block()` (do not search for a standalone `.connect()` method):
```java
ServerParameters params = ServerParameters.builder("java")
    .args(List.of("-cp", "target/MCP-1.0.0.jar", "edu.java.service.StdioServer"))
    .build();

StdioClientTransport transport = new StdioClientTransport(params, McpJsonDefaults.getMapper());

McpAsyncClient client = McpClient.async(transport)
    .clientInfo(McpSchema.Implementation.builder("my-client", "1.0.0").build())
    .build();

client.initialize().block(); // Triggers handshake
```

---

## 4. Stdio Transport Restrictions
When using the `stdio` transport, the parent and child processes communicate over standard input and standard output.
* **CRITICAL**: Do **never** print to `System.out` on the Server side outside of the protocol messages (e.g. no `System.out.println()`). Any stray characters on `stdout` will break JSON-RPC deserialization on the Client.
* **Safe Logging**: The logging framework (Log4j2) is pre-configured to output to `System.err` and to append to the log file `MCP.log`. Always route diagnostic messages through the `logger`.

---

## 5. How to Build, Run, and Test inside Bob

When executing development tasks, compiling changes, or testing end-to-end functionality, Bob must use the following commands and patterns to ensure the correct Java 21 and Maven toolchains are active.

### 5.1 Environmental Context (Mandatory)
In the Windows host environment, standard command terminals do not have Maven or Java 21 configured by default. Bob **MUST** source the setup scripts before executing any build or execution command:
* Maven Setup: `D:\Development\SetupEnvMaven.cmd`
* Java 21 Setup: `D:\Development\SetupEnvJava21.cmd`

### 5.2 Compiling & Packaging (The Build Command)
To compile the classes, verify dependencies, and package the shaded JAR, run the following unified command:
```powershell
cmd.exe /c "D:\Development\SetupEnvMaven.cmd && D:\Development\SetupEnvJava21.cmd && mvn clean package"
```

### 5.3 Launching the Application
Since the launcher is fully integrated into `edu.java.MCP`, both the server and client can be run from the packaged shaded fat-JAR:

* **Running the Client (Universal Feature Dumper)**:
  This launches the client, which in turn starts our in-process server as a child process, dumps its Tools, and executes tool verification.
  ```powershell
  cmd.exe /c "D:\Development\SetupEnvJava21.cmd && java -jar target\MCP-1.0.0.jar client"
  ```

* **Running the Server directly (Standalone Stdio Daemon)**:
  Launches our server standalone, ready to receive JSON-RPC standard I/O streams from any external host or client.
  ```powershell
  cmd.exe /c "D:\Development\SetupEnvJava21.cmd && java -jar target\MCP-1.0.0.jar server"
  ```
