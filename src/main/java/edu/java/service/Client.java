package edu.java.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import edu.java.MCP;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult.StopReason;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.ListPromptsResult;
import io.modelcontextprotocol.spec.McpSchema.ListResourceTemplatesResult;
import io.modelcontextprotocol.spec.McpSchema.ListResourcesResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import reactor.core.publisher.Mono;

/**
 * Universal MCP Client implementation using the <a href="https://github.com/modelcontextprotocol/java-sdk">MCP Java SDK</a>.
 * This client connects to any compliant MCP StdioServer over stdio, queries its capabilities, dumps all available tools, and
 * runs demonstration calls on them.
 */
public class Client {

    /** Default logger (using appender that includes e.g. timestamp, ...). */
    private static final Logger logger = LogManager.getLogger(Client.class);

    /** Sysout logger (no formatting). */
    protected final Logger loggerSysout = LogManager.getLogger("edu.java.Sysout");

    public static void main(final String[] args) {
        new Client().process(args);
    }

    private void process(final String[] args) {
        loggerSysout.info("=================================================");
        loggerSysout.info("               {}               ", MCP.MCP_JAVA_SDK_CLIENT);
        loggerSysout.info("=================================================");

        boolean useSse = false;
        boolean useStreamable = false;
        /** SSE MCP Server address (typically http://127.0.0.1:8080). */
        String sseUrl = SseServer.SSE_SERVER;
        /** Streamable HTTP MCP Server address (typically http://127.0.0.1:8081). */
        String streamableUrl = StreamableSseServer.STREAMABLE_SERVER;

        String command = "";
        List<String> commandArgs = new ArrayList<>();

        // If arguments are provided, determine if it's SSE, Streamable HTTP, or Stdio.
        if (args.length > 0) {
            if ("sse".equalsIgnoreCase(args[0])) {
                useSse = true;
                if (args.length > 1) {
                    sseUrl = args[1];
                }
                loggerSysout.info("Configuring client to connect to remote {}", MCP.MCP_JAVA_SDK_SSE_SERVER);
                loggerSysout.info("SSE Endpoint URL: {}", sseUrl);
            } else if ("streamable".equalsIgnoreCase(args[0])) {
                useStreamable = true;
                if (args.length > 1) {
                    streamableUrl = args[1];
                }
                loggerSysout.info("Configuring client to connect to remote {}", MCP.MCP_JAVA_SDK_STREAMABLE_SERVER);
                loggerSysout.info("Streamable HTTP Base URL: {}", streamableUrl);
            } else {
                command = args[0];
                if (args.length > 1) {
                    commandArgs.addAll(Arrays.asList(args).subList(1, args.length));
                }
                loggerSysout.info("Configuring client to launch custom {}", MCP.MCP_JAVA_SDK_STDIO_SERVER);
                loggerSysout.info("Command: {}", command);
                loggerSysout.info("Arguments: {}", commandArgs);
            }
        } else {
            command = "java";
            commandArgs.add("-cp");
            commandArgs.add(System.getProperty("java.class.path"));
            commandArgs.add(StdioServer.class.getName());
            loggerSysout.info("No target server specified. Defaulting to {}", MCP.MCP_JAVA_SDK_STDIO_SERVER);
            loggerSysout.info("Command: {} [using current classpath]", command);
        }
        loggerSysout.info("-------------------------------------------------");

        McpAsyncClient client = null;
        try {
            McpClientTransport transport;

            if (useSse) {
                // 1 & 2. Initialize the SSE Client Transport.
                // HttpClientSseClientTransport is @Deprecated in SDK 2.0.1 (SSE superseded by Streamable HTTP).
                // Retained intentionally: this client supports --sse mode for backward-compatibility testing
                // against MCP servers that have not yet migrated to the Streamable HTTP transport.
                @SuppressWarnings("deprecation")
                var sseTransport = HttpClientSseClientTransport.builder(sseUrl).build();
                transport = sseTransport;
            } else if (useStreamable) {
                // 1 & 2. Initialize the Streamable HTTP Client Transport
                transport = HttpClientStreamableHttpTransport.builder(streamableUrl)
                        .endpoint(StreamableSseServer.STREAMABLE_ENDPOINT).build();
            } else {
                // 1. Configure the server parameters
                ServerParameters params = ServerParameters.builder(command).args(commandArgs).build();

                // 2. Initialize the Stdio Client Transport
                StdioClientTransport stdioTransport = new StdioClientTransport(params, McpJsonDefaults.getMapper());

                // Redirect child process stderr to the client logger
                stdioTransport.setStdErrorHandler(line -> {
                    logger.info("[StdioServer-Stderr] {}", line);
                });

                transport = stdioTransport;
            }

            // 3. Build the McpAsyncClient with sampling capabilities
            ClientCapabilities clientCapabilities = ClientCapabilities.builder().sampling().build();

            client = McpClient.async(transport)
                    .clientInfo(McpSchema.Implementation.builder(MCP.MCP_JAVA_SDK_CLIENT, MCP.MCP_VERSION).build())
                    .capabilities(clientCapabilities).sampling(req -> {
                        logger.info("Client received sampling request from server: {}", req);
                        String userText = ((TextContent) req.messages().getLast().content()).text();
                        return Mono.just(CreateMessageResult.builder(Role.ASSISTANT,
                                TextContent.builder("[mock LLM response] " + userText).build(), "mock-model")
                                .stopReason(StopReason.END_TURN).build());
                    }).build();

            // 4. Initialize the connection
            loggerSysout.info("Launching target MCP server and initializing session...");
            client.initialize().block();
            loggerSysout.info("Initialization complete! Connected successfully.");
            loggerSysout.info("StdioServer Name:    {}", client.getServerInfo().name());
            loggerSysout.info("StdioServer Version: {}", client.getServerInfo().version());
            loggerSysout.info("-------------------------------------------------");

            // 5. Query and dump tools
            loggerSysout.info("Querying registered Tools from StdioServer...");
            ListToolsResult toolsResult = client.listTools().block();
            List<Tool> tools = (toolsResult != null) ? toolsResult.tools() : new ArrayList<>();

            loggerSysout.info("--- DUMPED TOOLS ({}) ---", tools.size());
            for (Tool tool : tools) {
                loggerSysout.info("[Tool] name: \"{}\"", tool.name());
                loggerSysout.info("  description: \"{}\"", tool.description());
                loggerSysout.info("  inputSchema: ", tool.inputSchema());
            }
            loggerSysout.info("-------------------------------------------------");

            // 6. Demonstrate tool execution (if those tools are present)
            loggerSysout.info("--- RUNNING TOOL DEMONSTRATIONS ---");

            // Test 'echo' if present
            boolean hasEcho = tools.stream().anyMatch(t -> "echo".equals(t.name()));
            if (hasEcho) {
                loggerSysout.info("Calling 'echo' tool...");
                CallToolResult echoRes = client.callTool(CallToolRequest.builder("echo")
                        .arguments(Map.of("message", "Hello from the Universal Java Client!")).build()).block();
                if (echoRes != null && !echoRes.isError() && !echoRes.content().isEmpty()) {
                    String responseText = ((TextContent) echoRes.content().getFirst()).text();
                    loggerSysout.info("  Response -> \"{}\"", responseText);
                } else {
                    loggerSysout.error("  Response -> Error: ", (echoRes != null ? echoRes.isError() : "null"));
                }
            }

            // Test 'add' if present
            boolean hasAdd = tools.stream().anyMatch(t -> "add".equals(t.name()));
            if (hasAdd) {
                loggerSysout.info("Calling 'add' tool...");
                CallToolResult addRes = client
                        .callTool(CallToolRequest.builder("add").arguments(Map.of("a", "15", "b", "27")).build()).block();
                if (addRes != null && !addRes.isError() && !addRes.content().isEmpty()) {
                    String responseText = ((TextContent) addRes.content().getFirst()).text();
                    loggerSysout.info("  Response -> Sum of 15 + 27 = ", responseText);
                } else {
                    loggerSysout.info("  Response -> Error");
                }
            }

            // Test 'current_time' if present
            boolean hasTime = tools.stream().anyMatch(t -> "current_time".equals(t.name()));
            if (hasTime) {
                loggerSysout.info("Calling 'current_time' tool...");
                CallToolResult timeRes = client.callTool(CallToolRequest.builder("current_time").arguments(Map.of()).build())
                        .block();
                if (timeRes != null && !timeRes.isError() && !timeRes.content().isEmpty()) {
                    String responseText = ((TextContent) timeRes.content().getFirst()).text();
                    loggerSysout.info("  Response -> ISO-8601 server time: ", responseText);
                } else {
                    loggerSysout.info("  Response -> Error");
                }
            }

            loggerSysout.info("-------------------------------------------------");
            loggerSysout.info("Tool demonstrations completed successfully.");

            // 7. Query and dump resources
            loggerSysout.info("Querying registered Resources from StdioServer...");
            ListResourcesResult resourcesResult = client.listResources().block();
            List<Resource> resources = (resourcesResult != null) ? resourcesResult.resources() : new ArrayList<>();

            loggerSysout.info("--- DUMPED RESOURCES ({}) ---", resources.size());
            for (Resource r : resources) {
                loggerSysout.info("[Resource] name: \"{}\"", r.name());
                loggerSysout.info("  uri: \"{}\"", r.uri());
                loggerSysout.info("  mimeType: \"{}\"", r.mimeType());
                loggerSysout.info("  description: \"{}\"", r.description());

                // Read resource content
                try {
                    ReadResourceResult readResult = client.readResource(ReadResourceRequest.builder(r.uri()).build()).block();
                    if (readResult != null && !readResult.contents().isEmpty()) {
                        String text = ((TextResourceContents) readResult.contents().getFirst()).text();
                        loggerSysout.info("  Content: ", (text.length() > 200 ? text.substring(0, 197) + "..." : text));
                    }
                } catch (Exception e) {
                    loggerSysout.error("  Error reading resource: ", e.getMessage());
                }
            }
            loggerSysout.info("-------------------------------------------------");

            // 8. Query and dump resource templates
            loggerSysout.info("Querying registered Resource Templates from StdioServer...");
            ListResourceTemplatesResult templatesResult = client.listResourceTemplates().block();
            List<ResourceTemplate> templates = (templatesResult != null) ? templatesResult.resourceTemplates()
                    : new ArrayList<>();

            loggerSysout.info("--- DUMPED RESOURCE TEMPLATES ({}) ---", templates.size());
            for (ResourceTemplate t : templates) {
                loggerSysout.info("[Resource Template] name: \"{}\"", t.name());
                loggerSysout.info("  uriTemplate: \"{}\"", t.uriTemplate());
                loggerSysout.info("  mimeType: \"{}\"", t.mimeType());
                loggerSysout.info("  description: \"{}\"", t.description());
            }

            // Demonstrate reading from template instance
            loggerSysout.info("Demonstrating reading from a Resource Template instance...");
            String templateInstanceUri = "mcp://poc/echo/Hello-From-Resource-Template";
            loggerSysout.info("Reading resource template instance: ", templateInstanceUri);
            try {
                ReadResourceResult readResult = client.readResource(ReadResourceRequest.builder(templateInstanceUri).build())
                        .block();
                if (readResult != null && !readResult.contents().isEmpty()) {
                    String text = ((TextResourceContents) readResult.contents().getFirst()).text();
                    loggerSysout.info("  Response -> \"{}\"", text);
                }
            } catch (Exception e) {
                loggerSysout.error("  Error reading template instance: ", e.getMessage());
            }
            loggerSysout.info("-------------------------------------------------");

            // 9. Query and dump prompts
            loggerSysout.info("Querying registered Prompts from StdioServer...");
            ListPromptsResult promptsResult = client.listPrompts().block();
            List<Prompt> prompts = (promptsResult != null) ? promptsResult.prompts() : new ArrayList<>();

            loggerSysout.info("--- DUMPED PROMPTS ({}) ---", prompts.size());
            for (Prompt p : prompts) {
                loggerSysout.info("[Prompt] name: \"{}\"", p.name());
                loggerSysout.info("  description: \"{}\"", p.description());
                loggerSysout.info("  arguments: ", p.arguments());
            }

            // Demonstrate prompt execution
            boolean hasSummarise = prompts.stream().anyMatch(p -> "summarise".equals(p.name()));
            if (hasSummarise) {
                loggerSysout.info("Calling 'summarise' prompt template...");
                try {
                    GetPromptResult promptRes = client.getPrompt(GetPromptRequest.builder("summarise").arguments(Map.of("text",
                            "The Model Context Protocol (MCP) is an open standard that enables developers to build secure, bidirectional connections between their AI models and their data sources.",
                            "points", "3")).build()).block();
                    if (promptRes != null && !promptRes.messages().isEmpty()) {
                        loggerSysout.info("  Prompt Description: ", promptRes.description());
                        for (PromptMessage msg : promptRes.messages()) {
                            loggerSysout.info("  [{}] Content: {}", msg.role(), ((TextContent) msg.content()).text());
                        }
                    }
                } catch (Exception e) {
                    loggerSysout.error("  Error calling prompt: ", e.getMessage());
                }
            }
            loggerSysout.info("-------------------------------------------------");

            // 10. Demonstrate Sampling via 'llm_expand' tool
            boolean hasLlmExpand = tools.stream().anyMatch(t -> "llm_expand".equals(t.name()));
            if (hasLlmExpand) {
                loggerSysout.info("--- DEMONSTRATING SAMPLING (via 'llm_expand' tool) ---");
                loggerSysout.info(
                        "Calling 'llm_expand' tool, which requires the server to call back to the client for LLM sampling...");
                try {
                    CallToolResult expandRes = client
                            .callTool(CallToolRequest.builder("llm_expand")
                                    .arguments(Map.of("phrase", "Model Context Protocol simplifies integrations")).build())
                            .block();
                    if (expandRes != null && !expandRes.isError() && !expandRes.content().isEmpty()) {
                        String text = ((TextContent) expandRes.content().getFirst()).text();
                        loggerSysout.info("  Expanded Response -> \"{}\"", text);
                    } else {
                        loggerSysout.warn("  Response -> Error: ", (expandRes != null ? expandRes.isError() : "null"));
                    }
                } catch (Exception e) {
                    loggerSysout.error("  Error calling llm_expand: ", e.getMessage());
                }
                loggerSysout.info("-------------------------------------------------");
            }

        } catch (Exception e) {
            loggerSysout.error("An error occurred in MCP Client: ", e.getMessage());
            logger.error("Error in MCP Client", e);
            throw new RuntimeException("MCP Client failed", e);
        } finally {
            if (client != null) {
                loggerSysout.info("Closing client connection...");
                try {
                    client.closeGracefully().block();
                } catch (Exception ignored) {
                }
                loggerSysout.info("Connection closed cleanly.");
            }
            loggerSysout.info("=================================================");
        }
    }

}
