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
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Universal MCP Client implementation using the <a href="https://github.com/modelcontextprotocol/java-sdk">MCP Java SDK</a>.
 * This client connects to any compliant MCP Server over stdio, queries its capabilities, dumps all available tools, and runs
 * demonstration calls on them.
 */
public class Client {

    /** Default logger (using appender that includes e.g. timestamp, ...). */
    private static final Logger logger = LogManager.getLogger(Client.class);

    public static void main(String[] args) {
        System.out.println("=================================================");
        System.out.println("               " + MCP.MCP_JAVA_SDK_CLIENT + "               ");
        System.out.println("=================================================");

        String command;
        List<String> commandArgs = new ArrayList<>();

        // If arguments are provided, use them as the server execution command and arguments.
        // Otherwise, default to launching our own Server class from the target jar.
        if (args.length > 0) {
            command = args[0];
            if (args.length > 1) {
                commandArgs.addAll(Arrays.asList(args).subList(1, args.length));
            }
            System.out.println("Configuring client to launch custom " + MCP.MCP_JAVA_SDK_SERVER + ":");
            System.out.println("Command: " + command);
            System.out.println("Arguments: " + commandArgs);
        } else {
            command = "java";
            commandArgs.add("-cp");
            commandArgs.add("target/MCP-" + MCP.MCP_VERSION + ".jar");
            commandArgs.add("edu.java.service.Server");
            System.out.println("No target server specified. Defaulting to " + MCP.MCP_JAVA_SDK_SERVER + ":");
            System.out.println("Command: " + command + " " + String.join(" ", commandArgs));
        }
        System.out.println("-------------------------------------------------");

        McpAsyncClient client = null;
        try {
            // 1. Configure the server parameters
            ServerParameters params = ServerParameters.builder(command).args(commandArgs).build();

            // 2. Initialize the Stdio Client Transport
            StdioClientTransport transport = new StdioClientTransport(params);

            // Redirect child process stderr to the client logger
            transport.setStdErrorHandler(line -> {
                logger.info("[Server-Stderr] {}", line);
            });

            // 3. Build the McpAsyncClient
            client = McpClient.async(transport)
                    .clientInfo(new McpSchema.Implementation(MCP.MCP_JAVA_SDK_CLIENT, MCP.MCP_VERSION)).build();

            // 4. Initialize the connection
            System.out.println("Launching target MCP server and initializing session...");
            client.initialize().block();
            System.out.println("Initialization complete! Connected successfully.");
            System.out.println("Server Name:    " + client.getServerInfo().name());
            System.out.println("Server Version: " + client.getServerInfo().version());
            System.out.println("-------------------------------------------------");

            // 5. Query and dump tools
            System.out.println("Querying registered Tools from Server...");
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

        } catch (Exception e) {
            System.err.println("\nAn error occurred in MCP Client: " + e.getMessage());
            logger.error("Error in MCP Client", e);
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
