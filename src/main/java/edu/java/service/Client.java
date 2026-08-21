package edu.java.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import edu.java.MCP;
import edu.java.service.StreamableSseServer;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
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

    public static void main(String[] args) {
        System.out.println("=================================================");
        System.out.println("               " + MCP.MCP_JAVA_SDK_CLIENT + "               ");
        System.out.println("=================================================");

        boolean useSse = false;
        boolean useStreamable = false;
        /** SSE MCP Server address (typically http://127.0.0.1:8080). */;
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
                System.out.println("Configuring client to connect to remote " + MCP.MCP_JAVA_SDK_SSE_SERVER + ":");
                System.out.println("SSE Endpoint URL: " + sseUrl);
            } else if ("streamable".equalsIgnoreCase(args[0])) {
                useStreamable = true;
                if (args.length > 1) {
                    streamableUrl = args[1];
                }
                System.out.println("Configuring client to connect to remote " + MCP.MCP_JAVA_SDK_STREAMABLE_SERVER + ":");
                System.out.println("Streamable HTTP Base URL: " + streamableUrl);
            } else {
                command = args[0];
                if (args.length > 1) {
                    commandArgs.addAll(Arrays.asList(args).subList(1, args.length));
                }
                System.out.println("Configuring client to launch custom " + MCP.MCP_JAVA_SDK_STDIO_SERVER + ":");
                System.out.println("Command: " + command);
                System.out.println("Arguments: " + commandArgs);
            }
        } else {
            command = "java";
            commandArgs.add("-cp");
            commandArgs.add(System.getProperty("java.class.path"));
            commandArgs.add("edu.java.service.StdioServer");
            System.out.println("No target server specified. Defaulting to " + MCP.MCP_JAVA_SDK_STDIO_SERVER + ":");
            System.out.println("Command: " + command + " [using current classpath]");
        }
        System.out.println("-------------------------------------------------");

        McpAsyncClient client = null;
        try {
            io.modelcontextprotocol.spec.McpClientTransport transport;

            if (useSse) {
                // 1 & 2. Initialize the SSE Client Transport
                @SuppressWarnings("removal")
                HttpClientSseClientTransport sseTransport = new HttpClientSseClientTransport(sseUrl);
                transport = sseTransport;
            } else if (useStreamable) {
                // 1 & 2. Initialize the Streamable HTTP Client Transport
                transport = HttpClientStreamableHttpTransport.builder(streamableUrl)
                        .endpoint(StreamableSseServer.STREAMABLE_ENDPOINT).build();
            } else {
                // 1. Configure the server parameters
                ServerParameters params = ServerParameters.builder(command).args(commandArgs).build();

                // 2. Initialize the Stdio Client Transport
                StdioClientTransport stdioTransport = new StdioClientTransport(params);

                // Redirect child process stderr to the client logger
                stdioTransport.setStdErrorHandler(line -> {
                    logger.info("[StdioServer-Stderr] {}", line);
                });

                transport = stdioTransport;
            }

            // 3. Build the McpAsyncClient with sampling capabilities
            ClientCapabilities clientCapabilities = ClientCapabilities.builder().sampling().build();

            client = McpClient.async(transport)
                    .clientInfo(new McpSchema.Implementation(MCP.MCP_JAVA_SDK_CLIENT, MCP.MCP_VERSION))
                    .capabilities(clientCapabilities).sampling(req -> {
                        logger.info("Client received sampling request from server: {}", req);
                        String userText = ((TextContent) req.messages().getLast().content()).text();
                        return Mono.just(CreateMessageResult.builder().role(Role.ASSISTANT)
                                .content(new TextContent("[mock LLM response] " + userText)).model("mock-model")
                                .stopReason(StopReason.END_TURN).build());
                    }).build();

            // 4. Initialize the connection
            System.out.println("Launching target MCP server and initializing session...");
            client.initialize().block();
            System.out.println("Initialization complete! Connected successfully.");
            System.out.println("StdioServer Name:    " + client.getServerInfo().name());
            System.out.println("StdioServer Version: " + client.getServerInfo().version());
            System.out.println("-------------------------------------------------");

            // 5. Query and dump tools
            System.out.println("Querying registered Tools from StdioServer...");
            ListToolsResult toolsResult = client.listTools().block();
            List<Tool> tools = (toolsResult != null) ? toolsResult.tools() : new ArrayList<>();

            System.out.println("\n--- DUMPED TOOLS (" + tools.size() + ") ---");
            for (Tool tool : tools) {
                System.out.println("\n[Tool] name: \"" + tool.name() + "\"");
                System.out.println("  description: \"" + tool.description() + "\"");
                System.out.println("  inputSchema: " + tool.inputSchema());
            }
            System.out.println("-------------------------------------------------");

            // 6. Demonstrate tool execution (if those tools are present)
            System.out.println("\n--- RUNNING TOOL DEMONSTRATIONS ---");

            // Test 'echo' if present
            boolean hasEcho = tools.stream().anyMatch(t -> "echo".equals(t.name()));
            if (hasEcho) {
                System.out.println("\nCalling 'echo' tool...");
                CallToolResult echoRes = client
                        .callTool(new CallToolRequest("echo", Map.of("message", "Hello from the Universal Java Client!")))
                        .block();
                if (echoRes != null && !echoRes.isError() && !echoRes.content().isEmpty()) {
                    String responseText = ((TextContent) echoRes.content().getFirst()).text();
                    System.out.println("  Response -> \"" + responseText + "\"");
                } else {
                    System.out.println("  Response -> Error: " + (echoRes != null ? echoRes.isError() : "null"));
                }
            }

            // Test 'add' if present
            boolean hasAdd = tools.stream().anyMatch(t -> "add".equals(t.name()));
            if (hasAdd) {
                System.out.println("\nCalling 'add' tool...");
                CallToolResult addRes = client.callTool(new CallToolRequest("add", Map.of("a", "15", "b", "27"))).block();
                if (addRes != null && !addRes.isError() && !addRes.content().isEmpty()) {
                    String responseText = ((TextContent) addRes.content().getFirst()).text();
                    System.out.println("  Response -> Sum of 15 + 27 = " + responseText);
                } else {
                    System.out.println("  Response -> Error");
                }
            }

            // Test 'current_time' if present
            boolean hasTime = tools.stream().anyMatch(t -> "current_time".equals(t.name()));
            if (hasTime) {
                System.out.println("\nCalling 'current_time' tool...");
                CallToolResult timeRes = client.callTool(new CallToolRequest("current_time", Map.of())).block();
                if (timeRes != null && !timeRes.isError() && !timeRes.content().isEmpty()) {
                    String responseText = ((TextContent) timeRes.content().getFirst()).text();
                    System.out.println("  Response -> ISO-8601 server time: " + responseText);
                } else {
                    System.out.println("  Response -> Error");
                }
            }

            System.out.println("\n-------------------------------------------------");
            System.out.println("Tool demonstrations completed successfully.");

            // 7. Query and dump resources
            System.out.println("\nQuerying registered Resources from StdioServer...");
            ListResourcesResult resourcesResult = client.listResources().block();
            List<Resource> resources = (resourcesResult != null) ? resourcesResult.resources() : new ArrayList<>();

            System.out.println("\n--- DUMPED RESOURCES (" + resources.size() + ") ---");
            for (Resource r : resources) {
                System.out.println("\n[Resource] name: \"" + r.name() + "\"");
                System.out.println("  uri: \"" + r.uri() + "\"");
                System.out.println("  mimeType: \"" + r.mimeType() + "\"");
                System.out.println("  description: \"" + r.description() + "\"");

                // Read resource content
                try {
                    ReadResourceResult readResult = client.readResource(new ReadResourceRequest(r.uri())).block();
                    if (readResult != null && !readResult.contents().isEmpty()) {
                        String text = ((TextResourceContents) readResult.contents().getFirst()).text();
                        System.out.println("  Content: " + (text.length() > 200 ? text.substring(0, 197) + "..." : text));
                    }
                } catch (Exception e) {
                    System.out.println("  Error reading resource: " + e.getMessage());
                }
            }
            System.out.println("-------------------------------------------------");

            // 8. Query and dump resource templates
            System.out.println("\nQuerying registered Resource Templates from StdioServer...");
            ListResourceTemplatesResult templatesResult = client.listResourceTemplates().block();
            List<ResourceTemplate> templates = (templatesResult != null) ? templatesResult.resourceTemplates()
                    : new ArrayList<>();

            System.out.println("\n--- DUMPED RESOURCE TEMPLATES (" + templates.size() + ") ---");
            for (ResourceTemplate t : templates) {
                System.out.println("\n[Resource Template] name: \"" + t.name() + "\"");
                System.out.println("  uriTemplate: \"" + t.uriTemplate() + "\"");
                System.out.println("  mimeType: \"" + t.mimeType() + "\"");
                System.out.println("  description: \"" + t.description() + "\"");
            }

            // Demonstrate reading from template instance
            System.out.println("\nDemonstrating reading from a Resource Template instance...");
            String templateInstanceUri = "mcp://poc/echo/Hello-From-Resource-Template";
            System.out.println("Reading resource template instance: " + templateInstanceUri);
            try {
                ReadResourceResult readResult = client.readResource(new ReadResourceRequest(templateInstanceUri)).block();
                if (readResult != null && !readResult.contents().isEmpty()) {
                    String text = ((TextResourceContents) readResult.contents().getFirst()).text();
                    System.out.println("  Response -> \"" + text + "\"");
                }
            } catch (Exception e) {
                System.out.println("  Error reading template instance: " + e.getMessage());
            }
            System.out.println("-------------------------------------------------");

            // 9. Query and dump prompts
            System.out.println("\nQuerying registered Prompts from StdioServer...");
            ListPromptsResult promptsResult = client.listPrompts().block();
            List<Prompt> prompts = (promptsResult != null) ? promptsResult.prompts() : new ArrayList<>();

            System.out.println("\n--- DUMPED PROMPTS (" + prompts.size() + ") ---");
            for (Prompt p : prompts) {
                System.out.println("\n[Prompt] name: \"" + p.name() + "\"");
                System.out.println("  description: \"" + p.description() + "\"");
                System.out.println("  arguments: " + p.arguments());
            }

            // Demonstrate prompt execution
            boolean hasSummarise = prompts.stream().anyMatch(p -> "summarise".equals(p.name()));
            if (hasSummarise) {
                System.out.println("\nCalling 'summarise' prompt template...");
                try {
                    GetPromptResult promptRes = client.getPrompt(new GetPromptRequest("summarise", Map.of("text",
                            "The Model Context Protocol (MCP) is an open standard that enables developers to build secure, bidirectional connections between their AI models and their data sources.",
                            "points", "3"))).block();
                    if (promptRes != null && !promptRes.messages().isEmpty()) {
                        System.out.println("  Prompt Description: " + promptRes.description());
                        for (PromptMessage msg : promptRes.messages()) {
                            System.out.println("  [" + msg.role() + "] Content: " + ((TextContent) msg.content()).text());
                        }
                    }
                } catch (Exception e) {
                    System.out.println("  Error calling prompt: " + e.getMessage());
                }
            }
            System.out.println("-------------------------------------------------");

            // 10. Demonstrate Sampling via 'llm_expand' tool
            boolean hasLlmExpand = tools.stream().anyMatch(t -> "llm_expand".equals(t.name()));
            if (hasLlmExpand) {
                System.out.println("\n--- DEMONSTRATING SAMPLING (via 'llm_expand' tool) ---");
                System.out.println(
                        "Calling 'llm_expand' tool, which requires the server to call back to the client for LLM sampling...");
                try {
                    CallToolResult expandRes = client.callTool(new CallToolRequest("llm_expand",
                            Map.of("phrase", "Model Context Protocol simplifies integrations"))).block();
                    if (expandRes != null && !expandRes.isError() && !expandRes.content().isEmpty()) {
                        String text = ((TextContent) expandRes.content().getFirst()).text();
                        System.out.println("  Expanded Response -> \"" + text + "\"");
                    } else {
                        System.out.println("  Response -> Error: " + (expandRes != null ? expandRes.isError() : "null"));
                    }
                } catch (Exception e) {
                    System.out.println("  Error calling llm_expand: " + e.getMessage());
                }
                System.out.println("-------------------------------------------------");
            }

        } catch (Exception e) {
            System.err.println("\nAn error occurred in MCP Client: " + e.getMessage());
            logger.error("Error in MCP Client", e);
            throw new RuntimeException("MCP Client failed", e);
        } finally {
            if (client != null) {
                System.out.println("Closing client connection...");
                try {
                    client.closeGracefully().block();
                } catch (Exception ignored) {
                }
                System.out.println("Connection closed cleanly.");
            }
            System.out.println("=================================================");
        }
    }

}
