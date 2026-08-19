package edu.java.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.java.MCP;
import edu.java.util.SchemaBuilder;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import reactor.core.publisher.Mono;

/**
 * MCP Server implementation using the <a href="https://github.com/modelcontextprotocol/java-sdk">MCP Java SDK</a>. This server
 * implements the core Tools primitive with stdio transport.
 */
public class Server {

    /** Default logger (using appender that includes e.g. timestamp, ...). */
    private static final Logger logger = LogManager.getLogger(Server.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new RuntimeException("Error converting schema to JSON string", e);
        }
    }

    public static void main(String[] args) {
        logger.info("Starting MCP Java SDK Server over stdio...");

        try {
            // 1. Initialize the Stdio Transport Provider
            StdioServerTransportProvider transportProvider = new StdioServerTransportProvider();

            // 2. Build Server Capabilities
            ServerCapabilities capabilities = ServerCapabilities.builder().tools(true).build();

            // 3. Build and configure the McpAsyncServer
            @SuppressWarnings("unused")
            McpAsyncServer server = McpServer.async(transportProvider).serverInfo(MCP.MCP_JAVA_SDK_SERVER, MCP.MCP_VERSION)
                    .capabilities(capabilities)

                    // --- Tool 1: echo ---
                    .tool(new Tool("echo", "Echoes the provided message back to the caller. Useful for smoke-testing.",
                            toJson(SchemaBuilder.singleStringParameter("message", "The text to echo back"))),
                            (exchange, arguments) -> {
                                logger.info("Executing tool 'echo' with arguments: {}", arguments);
                                String msg = (String) arguments.get("message");
                                return Mono.just(new CallToolResult(
                                        List.of(new TextContent(msg != null ? "Echo: " + msg : "Echo: ")), false));
                            })

                    // --- Tool 2: add ---
                    .tool(new Tool("add", "Adds two integers and returns the sum.",
                            toJson(SchemaBuilder.objectSchema("Two integer operands",
                                    Map.of("a", "First integer operand", "b", "Second integer operand"), List.of("a", "b")))),
                            (exchange, arguments) -> {
                                logger.info("Executing tool 'add' with arguments: {}", arguments);
                                try {
                                    int a = Integer.parseInt(arguments.get("a").toString());
                                    int b = Integer.parseInt(arguments.get("b").toString());
                                    return Mono
                                            .just(new CallToolResult(List.of(new TextContent(String.valueOf(a + b))), false));
                                } catch (Exception e) {
                                    logger.error("Error executing 'add' tool", e);
                                    return Mono.just(
                                            new CallToolResult(List.of(new TextContent("Error: " + e.getMessage())), true));
                                }
                            })

                    // --- Tool 3: current_time ---
                    .tool(new Tool("current_time", "Returns the current server date and time in ISO-8601 format.",
                            toJson(Map.of("type", "object", "properties", Map.of()))), (exchange, arguments) -> {
                                logger.info("Executing tool 'current_time'");
                                String now = Instant.now().toString();
                                return Mono.just(new CallToolResult(List.of(new TextContent(now)), false));
                            })
                    .build();

            logger.info("MCP Server started successfully and listening on stdin/stdout.");

            // Keep the main thread alive indefinitely to let background transport threads run
            Mono.never().block();

        } catch (Exception e) {
            logger.error("Fatal error starting MCP Server", e);
            System.err.println("Fatal error: " + e.getMessage());
            System.exit(1);
        }
    }
    
}
