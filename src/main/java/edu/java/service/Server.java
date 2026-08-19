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
import io.modelcontextprotocol.server.McpServerFeatures.AsyncPromptSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncResourceSpecification;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptArgument;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.SamplingMessage;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
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
            ServerCapabilities capabilities = ServerCapabilities.builder().tools(true).resources(false, true).prompts(true)
                    .build();

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

                    // --- Tool 4: llm_expand (uses sampling) ---
                    .tool(new Tool("llm_expand", "Asks the LLM to expand a short phrase into a full paragraph (uses sampling).",
                            toJson(SchemaBuilder.singleStringParameter("phrase", "The short phrase to expand"))),
                            (exchange, arguments) -> {
                                logger.info("Executing tool 'llm_expand' with arguments: {}", arguments);
                                String phrase = (String) arguments.get("phrase");
                                if (phrase == null) {
                                    return Mono.just(new CallToolResult(
                                            List.of(new TextContent("Error: missing phrase argument")), true));
                                }

                                CreateMessageRequest samplingRequest = CreateMessageRequest.builder()
                                        .messages(List.of(new SamplingMessage(Role.USER,
                                                new TextContent("Expand the following short phrase into one clear paragraph: "
                                                        + phrase))))
                                        .maxTokens(256).systemPrompt("You are a helpful writing assistant.").build();

                                return exchange.createMessage(samplingRequest).map(result -> {
                                    String generated = ((TextContent) result.content()).text();
                                    return new CallToolResult(List.of(new TextContent(generated)), false);
                                }).onErrorResume(e -> {
                                    logger.error("Sampling error in llm_expand", e);
                                    return Mono.just(new CallToolResult(
                                            List.of(new TextContent("Sampling error: " + e.getMessage())), true));
                                });
                            })

                    // --- Static Resources & Handlers ---
                    .resources(new AsyncResourceSpecification(new Resource("mcp://poc/info", "Server Info",
                            "Basic information about this MCP PoC server", "text/plain", null), (exchange, request) -> {
                                logger.info("Reading resource 'mcp://poc/info'");
                                return Mono.just(new ReadResourceResult(List.of(new TextResourceContents("mcp://poc/info",
                                        "text/plain",
                                        "MCP PoC Server\nSDK : MCP Java SDK\nJava: " + System.getProperty("java.version")))));
                            }),
                            new AsyncResourceSpecification(
                                    new Resource("mcp://poc/system-properties", "System Properties",
                                            "All JVM system properties as JSON", "application/json", null),
                                    (exchange, request) -> {
                                        logger.info("Reading resource 'mcp://poc/system-properties'");
                                        try {
                                            var props = System.getProperties();
                                            var sb = new StringBuilder("{");
                                            props.forEach((k, v) -> sb.append("\"").append(k).append("\":\"")
                                                    .append(v.toString().replace("\\", "\\\\").replace("\"", "\\\"")
                                                            .replace("\n", "\\n").replace("\r", "\\r"))
                                                    .append("\","));
                                            if (sb.charAt(sb.length() - 1) == ',')
                                                sb.deleteCharAt(sb.length() - 1);
                                            sb.append("}");
                                            return Mono.just(new ReadResourceResult(List.of(new TextResourceContents(
                                                    "mcp://poc/system-properties", "application/json", sb.toString()))));
                                        } catch (Exception e) {
                                            return Mono.error(e);
                                        }
                                    }),
                            new AsyncResourceSpecification(
                                    new Resource("mcp://poc/echo/Hello-From-Resource-Template", "Echo Template Instance",
                                            "Dynamic instance of the echo resource template", "text/plain", null),
                                    (exchange, request) -> {
                                        String uri = request.uri();
                                        logger.info("Reading template instance resource: {}", uri);
                                        String text = uri.substring(uri.lastIndexOf('/') + 1);
                                        return Mono.just(new ReadResourceResult(List
                                                .of(new TextResourceContents(uri, "text/plain", "Resource echo: " + text))));
                                    }),
                            new AsyncResourceSpecification(
                                    new Resource("mcp://poc/echo/junit-test", "Echo Template Junit Instance",
                                            "Junit instance of the echo resource template", "text/plain", null),
                                    (exchange, request) -> {
                                        String uri = request.uri();
                                        logger.info("Reading template instance resource: {}", uri);
                                        String text = uri.substring(uri.lastIndexOf('/') + 1);
                                        return Mono.just(new ReadResourceResult(List
                                                .of(new TextResourceContents(uri, "text/plain", "Resource echo: " + text))));
                                    }))
                    .resourceTemplates(new ResourceTemplate("mcp://poc/echo/{message}", "Echo Resource",
                            "Returns the {message} portion of the URI as text content", "text/plain", null))

                    // --- Prompts ---
                    .prompts(
                            new AsyncPromptSpecification(
                                    new Prompt("code_review", "Ask the LLM to review a code snippet",
                                            List.of(new PromptArgument("language", "Programming language", true),
                                                    new PromptArgument("code", "The code to review", true))),
                                    (exchange, request) -> {
                                        logger.info("Serving prompt 'code_review'");
                                        Map<String, Object> arguments = request.arguments();
                                        String language = arguments != null
                                                ? (String) arguments.getOrDefault("language", "unknown")
                                                : "unknown";
                                        String code = arguments != null ? (String) arguments.getOrDefault("code", "") : "";

                                        String systemText = "You are an expert " + language
                                                + " developer. Review the following code for correctness, "
                                                + "security, and style. Be concise.";

                                        String userText = "```" + language + "\n" + code + "\n```";

                                        return Mono.just(new GetPromptResult("Code review prompt for " + language,
                                                List.of(new PromptMessage(Role.USER, new TextContent(systemText)),
                                                        new PromptMessage(Role.USER, new TextContent(userText)))));
                                    }),
                            new AsyncPromptSpecification(
                                    new Prompt("summarise", "Summarise a block of text in a given number of bullet points",
                                            List.of(new PromptArgument("text", "Text to summarise", true), new PromptArgument(
                                                    "points", "Number of bullet points (default 5)", false))),
                                    (exchange, request) -> {
                                        logger.info("Serving prompt 'summarise'");
                                        Map<String, Object> arguments = request.arguments();
                                        String text = arguments != null ? (String) arguments.getOrDefault("text", "") : "";
                                        String points = arguments != null ? (String) arguments.getOrDefault("points", "5")
                                                : "5";

                                        String userText = "Summarise the following text in exactly " + points
                                                + " concise bullet points:\n\n" + text;

                                        return Mono.just(new GetPromptResult("Summarisation prompt",
                                                List.of(new PromptMessage(Role.USER, new TextContent(userText)))));
                                    }))

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
