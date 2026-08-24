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
import io.modelcontextprotocol.server.McpServerFeatures.AsyncResourceTemplateSpecification;
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
 * Abstract base class representing a generic MCP Server. Holds all shared MCP primitive factory methods, shared constants,
 * Jackson ObjectMapper instance, and base properties such as the serverType and serverName to resolve small parameter
 * differences.
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
        //@formatter:off
        Tool tool = Tool
                .builder("echo", SchemaBuilder.singleStringParameter("message", "The text to echo back"))
                .description("[MCP Primitives:Tool] Echoes the provided message back to the caller.")
                .build();
        //@formatter:on
        return new AsyncToolSpecification(tool, (exchange, callToolRequest) -> {
            logger.info("Executing [MCP Primitives:Tool] 'echo' with arguments: {}", callToolRequest.arguments());
            String msg = (String) callToolRequest.arguments().get("message");
            //@formatter:off
            return Mono.just(CallToolResult.builder()
                    .content(List.of(TextContent.builder(msg != null ? "Echo: " + msg : "Echo: ").build())).isError(false)
                    .build());
            //@formatter:on
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
        //@formatter:off
        Tool tool = Tool
                .builder("add",
                        SchemaBuilder.objectSchema("Two integer operands",
                                Map.of("a", "First integer operand", "b", "Second integer operand"), List.of("a", "b")))
                .description("[MCP Primitives:Tool] Adds two integers and returns the sum.")
                .build();
        //@formatter:on
        return new AsyncToolSpecification(tool, (exchange, callToolRequest) -> {
            logger.info("Executing [MCP Primitives:Tool] 'add' with arguments: {}", callToolRequest.arguments());
            try {
                int a = Integer.parseInt(callToolRequest.arguments().get("a").toString());
                int b = Integer.parseInt(callToolRequest.arguments().get("b").toString());
                return Mono.just(CallToolResult.builder().content(List.of(TextContent.builder(String.valueOf(a + b)).build()))
                        .isError(false).build());
            } catch (Exception e) {
                logger.error("Error executing 'add' tool", e);
                return Mono.just(CallToolResult.builder()
                        .content(List.of(TextContent.builder("Error: " + e.getMessage()).build())).isError(true).build());
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
        //@formatter:off
        Tool tool = Tool
                .builder("current_time", Map.of("type", "object", "properties", Map.of()))
                .description("[MCP Primitives:Tool] Returns the current server date and time in ISO-8601 format.")
                .build();
        //@formatter:on
        return new AsyncToolSpecification(tool, (exchange, callToolRequest) -> {
            logger.info("Executing [MCP Primitives:Tool] 'current_time'");
            String now = Instant.now().toString();
            return Mono
                    .just(CallToolResult.builder().content(List.of(TextContent.builder(now).build())).isError(false).build());
        });
    }

    /**
     * MCP Sampling Capability wrapper — {@code llm_expand}.
     *
     * <p>
     * Triggers a sampling round-trip back to the client. This method is registered as a Tool, but semantically wraps an LLM
     * text generation request. When invoked, it builds a {@link CreateMessageRequest} and calls
     * {@code exchange.createMessage(request)} to instruct the client's host LLM to expand the phrase.
     *
     * @return the fully configured {@link AsyncToolSpecification} ready for registration
     */
    protected AsyncToolSpecification createSamplingLlmExpand() {
        //@formatter:off
        Tool tool = Tool
                .builder("llm_expand", SchemaBuilder.singleStringParameter("phrase", "The short phrase to expand"))
                .description("[MCP Capability:Sampling] Asks the LLM to expand a short phrase into a full paragraph.")
                .build();
        //@formatter:on
        return new AsyncToolSpecification(tool, (exchange, callToolRequest) -> {
            logger.info("Executing [MCP Capability:Sampling] 'llm_expand' with arguments: {}", callToolRequest.arguments());
            String phrase = (String) callToolRequest.arguments().get("phrase");
            if (phrase == null) {
                return Mono.just(CallToolResult.builder()
                        .content(List.of(TextContent.builder("Error: missing phrase argument").build())).isError(true).build());
            }
            List<SamplingMessage> msgs = List.of(new SamplingMessage(Role.USER,
                    TextContent.builder("Expand the following short phrase into one clear paragraph: " + phrase).build()));
            CreateMessageRequest samplingRequest = CreateMessageRequest.builder(msgs, 256)
                    .systemPrompt("You are a helpful writing assistant.").build();
            return exchange.createMessage(samplingRequest).map(result -> {
                String generated = ((TextContent) result.content()).text();
                return CallToolResult.builder().content(List.of(TextContent.builder(generated).build())).isError(false).build();
            }).onErrorResume(e -> {
                logger.error("Sampling error in [MCP Primitives:Tool] 'llm_expand'", e);
                return Mono.just(CallToolResult.builder()
                        .content(List.of(TextContent.builder("Sampling error: " + e.getMessage()).build())).isError(true)
                        .build());
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
     * Static informational resource: returns basic information about the running server, SDK name, and the active JVM version
     * resolved at request time. Served as {@code text/plain}.
     *
     * @return the fully configured {@link AsyncResourceSpecification} ready for registration
     */
    protected AsyncResourceSpecification createResourceInfo() {
        //@formatter:off
        Resource resource = Resource
                .builder("mcp://poc/info", serverName)
                .description("[MCP Primitives:Resource] Return basic information about this MCP PoC server")
                .mimeType("text/plain")
                .build();
        //@formatter:on
        return new AsyncResourceSpecification(resource, (exchange, request) -> {
            logger.info("Reading [MCP Primitives:Resource] 'mcp://poc/info'");
            return Mono
                    .just(ReadResourceResult
                            .builder(
                                    List.of(TextResourceContents
                                            .builder("mcp://poc/info",
                                                    "MCP PoC " + serverType + "\nSDK : MCP Java SDK\nJava: "
                                                            + System.getProperty("java.version"))
                                            .mimeType("text/plain").build()))
                            .build());
        });
    }

    /**
     * MCP Resource — {@code mcp://poc/system-properties}.
     *
     * <p>
     * Server properties dump: dynamically gathers all active JVM system properties, maps them to a JSON object, and escapes
     * slashes and double quotes safely. Served as {@code application/json}.
     *
     * @return the fully configured {@link AsyncResourceSpecification} ready for registration
     */
    protected AsyncResourceSpecification createResourceSystemProperties() {
        //@formatter:off
        Resource resource = Resource
                .builder("mcp://poc/system-properties", "System Properties")
                .description("[MCP Primitives:Resource] Return all JVM system properties and environment variables as JSON")
                .mimeType("application/json")
                .build();
        //@formatter:on
        return new AsyncResourceSpecification(resource, (exchange, request) -> {
            logger.info("Reading [MCP Primitives:Resource] 'mcp://poc/system-properties'");
            try {
                var sb = new StringBuilder("{");

                // 1. Serialize JVM System Properties
                sb.append("\"Properties\":{");
                var props = System.getProperties();
                props.forEach((k, v) -> sb.append("\"").append(k).append("\":\"").append(
                        v.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"))
                        .append("\","));
                if (sb.charAt(sb.length() - 1) == ',') {
                    sb.deleteCharAt(sb.length() - 1);
                }
                sb.append("},");

                // 2. Serialize OS Environment Variables
                sb.append("\"Environment\":{");
                var env = System.getenv();
                env.forEach((k, v) -> sb.append("\"").append(k).append("\":\"")
                        .append(v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"))
                        .append("\","));
                if (sb.charAt(sb.length() - 1) == ',') {
                    sb.deleteCharAt(sb.length() - 1);
                }
                sb.append("}");

                sb.append("}");
                return Mono.just(ReadResourceResult.builder(List.of(TextResourceContents
                        .builder("mcp://poc/system-properties", sb.toString()).mimeType("application/json").build())).build());
            } catch (Exception e) {
                return Mono.error(e);
            }
        });
    }

    /**
     * MCP Resource Template instance — {@code mcp://poc/echo/Hello-From-Resource-Template}.
     *
     * <p>
     * Demonstration static resource representing an instantiated template pattern path. Extracts the message portion and
     * returns it as plain text.
     *
     * @return the fully configured {@link AsyncResourceSpecification} ready for registration
     */
    protected AsyncResourceSpecification createResourceEchoHello() {
        //@formatter:off
        Resource resource = Resource
                .builder("mcp://poc/echo/Hello-From-Resource-Template", "Echo Hello Resource")
                .description(
                        "[MCP Primitives:Resource] Return static resource whose URI conforms to the echo resource template")
                .mimeType("text/plain")
                .build();
        //@formatter:on
        return new AsyncResourceSpecification(resource, (exchange, request) -> {
            String uri = request.uri();
            logger.info("Reading [MCP Primitives:Resource] template instance: {}", uri);
            String text = uri.substring(uri.lastIndexOf('/') + 1);
            return Mono.just(ReadResourceResult
                    .builder(
                            List.of(TextResourceContents.builder(uri, "Resource echo: " + text).mimeType("text/plain").build()))
                    .build());
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
        //@formatter:off
        Resource resource = Resource
                .builder("mcp://poc/echo/junit-test", "Echo Junit Resource")
                .description("[MCP Primitives:Resource] Return static resource whose URI conforms to the echo resource template, used in Junit tests")
                .mimeType("text/plain")
                .build();
        //@formatter:on
        return new AsyncResourceSpecification(resource, (exchange, request) -> {
            String uri = request.uri();
            logger.info("Reading [MCP Primitives:Resource] template instance: {}", uri);
            String text = uri.substring(uri.lastIndexOf('/') + 1);
            return Mono.just(ReadResourceResult
                    .builder(
                            List.of(TextResourceContents.builder(uri, "Resource echo: " + text).mimeType("text/plain").build()))
                    .build());
        });
    }

    /**
     * MCP Resource Template — {@code mcp://poc/echo/{message}}.
     *
     * <p>
     * Advertises support for the dynamic echo resource template. The handler extracts the {@code {message}} portion of the URI
     * and returns it as plain text. Delegates to the same extraction logic used by the static resource specifications.
     *
     * @return the fully configured {@link AsyncResourceTemplateSpecification} ready for registration
     */
    protected AsyncResourceTemplateSpecification createResourceTemplateEcho() {
        //@formatter:off
        ResourceTemplate resourceTemplate = ResourceTemplate
                .builder("mcp://poc/echo/{message}", "Echo Resource")
                .description("[MCP Primitives:Resource] Returns the {message} portion of the URI as plain text")
                .mimeType("text/plain")
                .build();
        //@formatter:on
        return new AsyncResourceTemplateSpecification(resourceTemplate, (exchange, request) -> {
            String uri = request.uri();
            logger.info("Reading [MCP Primitives:ResourceTemplate] template instance: {}", uri);
            String text = uri.substring(uri.lastIndexOf('/') + 1);
            return Mono.just(ReadResourceResult
                    .builder(
                            List.of(TextResourceContents.builder(uri, "Resource echo: " + text).mimeType("text/plain").build()))
                    .build());
        });
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
        //@formatter:off
        Prompt prompt = Prompt
                .builder("code_review")
                .description("[MCP Primitives:Prompt] Ask the LLM to review a code snippet")
                .arguments(
                        List.of(PromptArgument.builder("language").description("Programming language").required(true).build(),
                                PromptArgument.builder("code").description("The code to review").required(true).build()))
                .build();
        //@formatter:on
        return new AsyncPromptSpecification(prompt, (exchange, request) -> {
            logger.info("Serving [MCP Primitives:Prompt] 'code_review'");
            Map<String, Object> arguments = request.arguments();
            String language = arguments != null ? (String) arguments.getOrDefault("language", "unknown") : "unknown";
            String code = arguments != null ? (String) arguments.getOrDefault("code", "") : "";
            String systemText = "You are an expert " + language + " developer. Review the following code for correctness, "
                    + "security, and style. Be concise.";
            String userText = "```" + language + "\n" + code + "\n```";
            return Mono.just(GetPromptResult
                    .builder(List.of(new PromptMessage(Role.USER, TextContent.builder(systemText).build()),
                            new PromptMessage(Role.USER, TextContent.builder(userText).build())))
                    .description("Code review prompt for " + language).build());
        });
    }

    /**
     * MCP Prompt — {@code summarise}.
     *
     * <p>
     * Summarisation prompt generator: accepts required argument {@code text} and optional argument {@code points} (which
     * defaults to {@code "5"}). Formulates a single system prompt message asking the host LLM to break the text down into
     * concise bullet points.
     *
     * @return the fully configured {@link AsyncPromptSpecification} ready for registration
     */
    protected AsyncPromptSpecification createPromptSummarise() {
        //@formatter:off
        Prompt prompt = Prompt
                .builder("summarise")
                .description("[MCP Primitives:Prompt] Summarise a block of text in a given number of bullet points")
                .arguments(List.of(PromptArgument.builder("text").description("Text to summarise").required(true).build(),
                        PromptArgument.builder("points").description("Number of bullet points (default 5)").required(false)
                                .build()))
                .build();
        //@formatter:on
        return new AsyncPromptSpecification(prompt, (exchange, request) -> {
            logger.info("Serving [MCP Primitives:Prompt] 'summarise'");
            Map<String, Object> arguments = request.arguments();
            String text = arguments != null ? (String) arguments.getOrDefault("text", "") : "";
            String points = arguments != null ? (String) arguments.getOrDefault("points", "5") : "5";
            String userText = "Summarise the following text in exactly " + points + " concise bullet points:\n\n" + text;
            return Mono
                    .just(GetPromptResult.builder(List.of(new PromptMessage(Role.USER, TextContent.builder(userText).build())))
                            .description("Summarisation prompt").build());
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
