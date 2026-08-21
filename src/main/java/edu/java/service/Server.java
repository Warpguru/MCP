package edu.java.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.java.util.SchemaBuilder;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncPromptSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncResourceSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
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
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import reactor.core.publisher.Mono;

/**
 * Abstract base class representing a generic MCP Server.
 * Holds all shared MCP primitive factory methods, shared constants, Jackson ObjectMapper instance,
 * and base properties such as the serverType and serverName to resolve small parameter differences.
 */
public abstract class Server {

    /** Default logger resolved dynamically based on the concrete subclass name. */
    protected final Logger logger = LogManager.getLogger(getClass());

    /** Sysout logger (no formatting). */
    protected final Logger loggerSysout = LogManager.getLogger("edu.java.Sysout");

    /** Shared Jackson object mapper instance. */
    protected static final ObjectMapper objectMapper = new ObjectMapper();

    /** The dynamic server name used for logging and Info resource descriptors. */
    protected final String serverType;

    /** The user-friendly resource display name. */
    protected final String serverName;

    /**
     * Protected constructor initializing base properties.
     *
     * @param serverType descriptor used inside the info resource content (e.g., "StdioServer" or "SseServer")
     * @param serverName user-friendly name used for info resource naming (e.g., "StdioServer Info" or "SseServer Info")
     */
    protected Server(final String serverType, final String serverName) {
        this.serverType = serverType;
        this.serverName = serverName;
    }

    // -------------------------------------------------------------------------
    // Tool factory methods
    // -------------------------------------------------------------------------

    /**
     * MCP Tool — {@code echo}.
     *
     * <p>
     * Smoke-test tool: returns the caller's message prefixed with {@code "Echo: "}. Accepts a single required string parameter
     * {@code message}.
     *
     * @return the fully configured {@link AsyncToolSpecification} ready for registration
     */
    protected AsyncToolSpecification createToolEcho() {
        Tool tool = new Tool("echo", "[MCP Primitives:Tool] Echoes the provided message back to the caller.",
                toJson(SchemaBuilder.singleStringParameter("message", "The text to echo back")));
        return new AsyncToolSpecification(tool, (exchange, arguments) -> {
            logger.info("Executing [MCP Primitives:Tool] 'echo' with arguments: {}", arguments);
            String msg = (String) arguments.get("message");
            return Mono.just(new CallToolResult(List.of(new TextContent(msg != null ? "Echo: " + msg : "Echo: ")), false));
        });
    }

    /**
     * MCP Tool — {@code add}.
     *
     * <p>
     * Arithmetic tool: parses two required integer parameters {@code a} and {@code b} and returns their sum as a string.
     * Returns an error result (with {@code isError=true}) rather than throwing if either argument cannot be parsed as an
     * integer, so that the MCP client receives a well-formed error message instead of a protocol fault.
     *
     * @return the fully configured {@link AsyncToolSpecification} ready for registration
     */
    protected AsyncToolSpecification createToolAdd() {
        Tool tool = new Tool("add", "[MCP Primitives:Tool] Adds two integers and returns the sum.",
                toJson(SchemaBuilder.objectSchema("Two integer operands",
                        Map.of("a", "First integer operand", "b", "Second integer operand"), List.of("a", "b"))));
        return new AsyncToolSpecification(tool, (exchange, arguments) -> {
            logger.info("Executing [MCP Primitives:Tool] 'add' with arguments: {}", arguments);
            try {
                int a = Integer.parseInt(arguments.get("a").toString());
                int b = Integer.parseInt(arguments.get("b").toString());
                return Mono.just(new CallToolResult(List.of(new TextContent(String.valueOf(a + b))), false));
            } catch (Exception e) {
                logger.error("Error executing 'add' tool", e);
                return Mono.just(new CallToolResult(List.of(new TextContent("Error: " + e.getMessage())), true));
            }
        });
    }

    /**
     * MCP Tool — {@code current_time}.
     *
     * <p>
     * Time-query utility: returns the current server time in ISO-8601 UTC format. Accepts no parameters.
     *
     * @return the fully configured {@link AsyncToolSpecification} ready for registration
     */
    protected AsyncToolSpecification createToolCurrentTime() {
        Tool tool = new Tool("current_time",
                "[MCP Primitives:Tool] Returns the current server date and time in ISO-8601 format.",
                toJson(Map.of("type", "object", "properties", Map.of())));
        return new AsyncToolSpecification(tool, (exchange, arguments) -> {
            logger.info("Executing [MCP Primitives:Tool] 'current_time'");
            String now = Instant.now().toString();
            return Mono.just(new CallToolResult(List.of(new TextContent(now)), false));
        });
    }

    /**
     * MCP Sampling Capability wrapper — {@code llm_expand}.
     *
     * <p>
     * Triggers a sampling round-trip back to the client. This method is registered as a Tool, but semantically wraps 
     * an LLM text generation request. When invoked, it builds a {@link CreateMessageRequest} and calls 
     * {@code exchange.createMessage(request)} to instruct the client's host LLM to expand the phrase.
     *
     * @return the fully configured {@link AsyncToolSpecification} ready for registration
     */
    protected AsyncToolSpecification createSamplingLlmExpand() {
        Tool tool = new Tool("llm_expand",
                "[MCP Capability:Sampling] Asks the LLM to expand a short phrase into a full paragraph.",
                toJson(SchemaBuilder.singleStringParameter("phrase", "The short phrase to expand")));
        return new AsyncToolSpecification(tool, (exchange, arguments) -> {
            logger.info("Executing [MCP Capability:Sampling] 'llm_expand' with arguments: {}", arguments);
            String phrase = (String) arguments.get("phrase");
            if (phrase == null) {
                return Mono.just(new CallToolResult(List.of(new TextContent("Error: missing phrase argument")), true));
            }
            CreateMessageRequest samplingRequest = CreateMessageRequest.builder()
                    .messages(List.of(new SamplingMessage(Role.USER,
                            new TextContent("Expand the following short phrase into one clear paragraph: " + phrase))))
                    .maxTokens(256).systemPrompt("You are a helpful writing assistant.").build();
            return exchange.createMessage(samplingRequest).map(result -> {
                String generated = ((TextContent) result.content()).text();
                return new CallToolResult(List.of(new TextContent(generated)), false);
            }).onErrorResume(e -> {
                logger.error("Sampling error in [MCP Primitives:Tool] 'llm_expand'", e);
                return Mono.just(new CallToolResult(List.of(new TextContent("Sampling error: " + e.getMessage())), true));
            });
        });
    }

    // -------------------------------------------------------------------------
    // Resource factory methods
    // -------------------------------------------------------------------------

    /**
     * MCP Resource — {@code mcp://poc/info}.
     *
     * <p>
     * Static informational resource: returns basic information about the running server, SDK name, and the active JVM 
     * version resolved at request time. Served as {@code text/plain}.
     *
     * @return the fully configured {@link AsyncResourceSpecification} ready for registration
     */
    protected AsyncResourceSpecification createResourceInfo() {
        Resource resource = new Resource("mcp://poc/info", serverName,
                "[MCP Primitives:Resource] Return basic information about this MCP PoC server", "text/plain", null);
        return new AsyncResourceSpecification(resource, (exchange, request) -> {
            logger.info("Reading [MCP Primitives:Resource] 'mcp://poc/info'");
            return Mono.just(new ReadResourceResult(List.of(new TextResourceContents("mcp://poc/info", "text/plain",
                    "MCP PoC " + serverType + "\nSDK : MCP Java SDK\nJava: " + System.getProperty("java.version")))));
        });
    }

    /**
     * MCP Resource — {@code mcp://poc/system-properties}.
     *
     * <p>
     * Server properties dump: dynamically gathers all active JVM system properties, maps them to a JSON object, and 
     * escapes slashes and double quotes safely. Served as {@code application/json}.
     *
     * @return the fully configured {@link AsyncResourceSpecification} ready for registration
     */
    protected AsyncResourceSpecification createResourceSystemProperties() {
        Resource resource = new Resource("mcp://poc/system-properties", "System Properties",
                "[MCP Primitives:Resource] Return all JVM system properties as JSON", "application/json", null);
        return new AsyncResourceSpecification(resource, (exchange, request) -> {
            logger.info("Reading [MCP Primitives:Resource] 'mcp://poc/system-properties'");
            try {
                var props = System.getProperties();
                var sb = new StringBuilder("{");
                props.forEach((k, v) -> sb.append("\"").append(k).append("\":\"").append(
                        v.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"))
                        .append("\","));
                if (sb.charAt(sb.length() - 1) == ',')
                    sb.deleteCharAt(sb.length() - 1);
                sb.append("}");
                return Mono.just(new ReadResourceResult(
                        List.of(new TextResourceContents("mcp://poc/system-properties", "application/json", sb.toString()))));
            } catch (Exception e) {
                return Mono.error(e);
            }
        });
    }

    /**
     * MCP Resource Template instance — {@code mcp://poc/echo/Hello-From-Resource-Template}.
     *
     * <p>
     * Demonstration static resource representing an instantiated template pattern path. Extracts the message portion 
     * and returns it as plain text.
     *
     * @return the fully configured {@link AsyncResourceSpecification} ready for registration
     */
    protected AsyncResourceSpecification createResourceEchoHello() {
        Resource resource = new Resource("mcp://poc/echo/Hello-From-Resource-Template", "Echo Hello Resource",
                "[MCP Primitives:Resource] Return static resource whose URI conforms to the echo resource template",
                "text/plain", null);
        return new AsyncResourceSpecification(resource, (exchange, request) -> {
            String uri = request.uri();
            logger.info("Reading [MCP Primitives:Resource] template instance: {}", uri);
            String text = uri.substring(uri.lastIndexOf('/') + 1);
            return Mono.just(
                    new ReadResourceResult(List.of(new TextResourceContents(uri, "text/plain", "Resource echo: " + text))));
        });
    }

    /**
     * MCP Resource Template instance — {@code mcp://poc/echo/junit-test}.
     *
     * <p>
     * Special mock template instance pre-registered specifically for the JUnit end-to-end integration test.
     *
     * @return the fully configured {@link AsyncResourceSpecification} ready for registration
     */
    protected AsyncResourceSpecification createResourceEchoJunit() {
        Resource resource = new Resource("mcp://poc/echo/junit-test", "Echo Junit Resource",
                "[MCP Primitives:Resource] Return static resource whose URI conforms to the echo resource template, used in Junit tests",
                "text/plain", null);
        return new AsyncResourceSpecification(resource, (exchange, request) -> {
            String uri = request.uri();
            logger.info("Reading [MCP Primitives:Resource] template instance: {}", uri);
            String text = uri.substring(uri.lastIndexOf('/') + 1);
            return Mono.just(
                    new ReadResourceResult(List.of(new TextResourceContents(uri, "text/plain", "Resource echo: " + text))));
        });
    }

    /**
     * MCP Resource Template — {@code mcp://poc/echo/{message}}.
     *
     * <p>
     * Advertises support for the dynamic echo resource template. This serves as metadata only; actual routing and retrieval 
     * is resolved via exact pre-registered URI specifications in this SDK version.
     *
     * @return the fully configured {@link ResourceTemplate} ready for advertisement
     */
    protected ResourceTemplate createResourceTemplateEcho() {
        return new ResourceTemplate("mcp://poc/echo/{message}", "Echo Resource",
                "[MCP Primitives:Resource] Returns the {message} portion of the URI as plain text", "text/plain", null);
    }

    // -------------------------------------------------------------------------
    // Prompt factory methods
    // -------------------------------------------------------------------------

    /**
     * MCP Prompt — {@code code_review}.
     *
     * <p>
     * Code review prompt generator: accepts required arguments {@code language} and {@code code}. Returns a multi-message 
     * prompt instructing the host LLM to review the provided snippet for style, correctness, and security.
     *
     * @return the fully configured {@link AsyncPromptSpecification} ready for registration
     */
    protected AsyncPromptSpecification createPromptCodeReview() {
        Prompt prompt = new Prompt("code_review", "[MCP Primitives:Prompt] Ask the LLM to review a code snippet",
                List.of(new PromptArgument("language", "Programming language", true),
                        new PromptArgument("code", "The code to review", true)));
        return new AsyncPromptSpecification(prompt, (exchange, request) -> {
            logger.info("Serving [MCP Primitives:Prompt] 'code_review'");
            Map<String, Object> arguments = request.arguments();
            String language = arguments != null ? (String) arguments.getOrDefault("language", "unknown") : "unknown";
            String code = arguments != null ? (String) arguments.getOrDefault("code", "") : "";
            String systemText = "You are an expert " + language + " developer. Review the following code for correctness, "
                    + "security, and style. Be concise.";
            String userText = "```" + language + "\n" + code + "\n```";
            return Mono.just(new GetPromptResult("Code review prompt for " + language,
                    List.of(new PromptMessage(Role.USER, new TextContent(systemText)),
                            new PromptMessage(Role.USER, new TextContent(userText)))));
        });
    }

    /**
     * MCP Prompt — {@code summarise}.
     *
     * <p>
     * Summarisation prompt generator: accepts required argument {@code text} and optional argument {@code points} 
     * (which defaults to {@code "5"}). Formulates a single system prompt message asking the host LLM to break the text 
     * down into concise bullet points.
     *
     * @return the fully configured {@link AsyncPromptSpecification} ready for registration
     */
    protected AsyncPromptSpecification createPromptSummarise() {
        Prompt prompt = new Prompt("summarise",
                "[MCP Primitives:Prompt] Summarise a block of text in a given number of bullet points",
                List.of(new PromptArgument("text", "Text to summarise", true),
                        new PromptArgument("points", "Number of bullet points (default 5)", false)));
        return new AsyncPromptSpecification(prompt, (exchange, request) -> {
            logger.info("Serving [MCP Primitives:Prompt] 'summarise'");
            Map<String, Object> arguments = request.arguments();
            String text = arguments != null ? (String) arguments.getOrDefault("text", "") : "";
            String points = arguments != null ? (String) arguments.getOrDefault("points", "5") : "5";
            String userText = "Summarise the following text in exactly " + points + " concise bullet points:\n\n" + text;
            return Mono.just(new GetPromptResult("Summarisation prompt",
                    List.of(new PromptMessage(Role.USER, new TextContent(userText)))));
        });
    }

    /**
     * Helper to convert {@link Map} to {@code Json} format.
     */
    protected String toJson(final Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new RuntimeException("Error converting schema to JSON string", e);
        }
    }

}
