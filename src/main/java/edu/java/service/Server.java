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
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
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
 * MCP Server implementation using the <a href="https://github.com/modelcontextprotocol/java-sdk">MCP Java SDK</a>.
 *
 * <p>
 * Demonstrates all three MCP primitives — Tools, Resources, and Prompts — plus the Sampling capability. Transport selection and
 * primitive registration are intentionally separated:
 * <ul>
 * <li>{@code processTransport*()} methods — each configures one specific MCP Transport (e.g. stdio, SSE) and then delegates to
 * {@link #buildServer}.</li>
 * <li>{@link #buildServer} — transport-agnostic: registers all primitives on the supplied transport provider, builds the
 * server, and blocks the calling thread.</li>
 * </ul>
 *
 * <p>
 * When using the stdio transport the server must never write to {@code System.out} directly; all diagnostic output goes through
 * the logger, which is configured to write to {@code System.err} and the log file.
 */
public class Server {

    /** Default logger (using appender that includes e.g. timestamp, ...). */
    private static final Logger logger = LogManager.getLogger(Server.class);

    /** Jackson object mapper instance. */
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    /**
     * Application entry point.
     *
     * <p>
     * Instantiates the server and delegates to {@link #processTransportStdio()} so that the startup logic lives in an instance
     * method rather than a static context. Any uncaught exception is logged and printed to {@code System.err} before the
     * process exits with code {@code 1}.
     *
     * @param args command-line arguments (not used)
     */
    public static void main(String[] args) {
        try {
            new Server().processTransportStdio();
        } catch (Exception e) {
            logger.error("Fatal error starting " + MCP.MCP_JAVA_SDK_SERVER, e);
            System.err.println("Fatal error: " + e.getMessage());
            System.exit(1);
        }
    }

    // -------------------------------------------------------------------------
    // Transport entry points
    // -------------------------------------------------------------------------

    /**
     * MCP Transport — stdio.
     *
     * <p>
     * Configures a {@link StdioServerTransportProvider}, which reads JSON-RPC messages from {@code System.in} and writes
     * responses to {@code System.out}. This transport is used when the MCP client launches the server as a child process on the
     * same machine (e.g. IDE plugins, CLI tools).
     *
     * <p>
     * Delegates all primitive registration and server startup to
     * {@link #buildServer(io.modelcontextprotocol.spec.McpServerTransportProvider)}.
     */
    public void processTransportStdio() {
        logger.info("Starting " + MCP.MCP_JAVA_SDK_SERVER + " over stdio transport...");
        buildServer(new StdioServerTransportProvider());
    }

    // -------------------------------------------------------------------------
    // Transport-agnostic server builder
    // -------------------------------------------------------------------------

    /**
     * Registers all MCP primitives and starts the server on the given transport.
     *
     * <p>
     * This method is intentionally transport-agnostic: it accepts any
     * {@link io.modelcontextprotocol.spec.McpServerTransportProvider} so that a future {@code processTransportSse()} (or any
     * other transport) can reuse the same primitive registrations without duplication.
     *
     * <p>
     * After {@link McpAsyncServer} is built the method blocks the calling thread forever via {@code Mono.never()} so that the
     * reactive background threads that handle I/O keep running.
     *
     * @param transportProvider the configured transport to attach the server to
     */
    private void buildServer(io.modelcontextprotocol.spec.McpServerTransportProvider transportProvider) {
        ServerCapabilities capabilities = ServerCapabilities.builder().tools(true).resources(false, true).prompts(true).build();
        // MCP Tools (standard)
        AsyncToolSpecification toolEcho = createToolEcho();
        AsyncToolSpecification toolAdd = createToolAdd();
        AsyncToolSpecification toolCurrentTime = createToolCurrentTime();
        // MCP Sampling (technically registered as a Tool, semantically a capability)
        AsyncToolSpecification toolLlmExpand = createSamplingLlmExpand();
        // MCP Resources (static, concrete URIs)
        AsyncResourceSpecification resourceInfo = createResourceInfo();
        AsyncResourceSpecification resourceSystemProperties = createResourceSystemProperties();
        AsyncResourceSpecification resourceEchoHello = createResourceEchoHello();
        AsyncResourceSpecification resourceEchoJunit = createResourceEchoJunit();
        // MCP Resource Templates (URI patterns advertised to clients)
        ResourceTemplate resourceTemplateEcho = createResourceTemplateEcho();
        // MCP Prompts
        AsyncPromptSpecification promptCodeReview = createPromptCodeReview();
        AsyncPromptSpecification promptSummarise = createPromptSummarise();
        //@formatter:off
        @SuppressWarnings("unused")
        McpAsyncServer server = McpServer
            .async(transportProvider)
            .serverInfo(MCP.MCP_JAVA_SDK_SERVER, MCP.MCP_VERSION)
            .capabilities(capabilities)
            .tools(toolEcho, toolAdd, toolCurrentTime, toolLlmExpand)
            .resources(resourceInfo, resourceSystemProperties, resourceEchoHello, resourceEchoJunit)
            .resourceTemplates(resourceTemplateEcho)
            .prompts(promptCodeReview, promptSummarise)
            .build();
        //@formatter:on
        logger.info(MCP.MCP_JAVA_SDK_SERVER + " started successfully.");
        // Block the calling thread forever so the reactive transport threads keep running.
        // Mono.never() is a Reactor idiom that produces a Mono that never emits or completes.
        Mono.never().block();
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
    private AsyncToolSpecification createToolEcho() {
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
    private AsyncToolSpecification createToolAdd() {
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
     * Returns the current server UTC timestamp in ISO-8601 format (e.g. {@code 2025-06-01T12:00:00Z}). Accepts no parameters;
     * the input schema is an empty JSON object.
     *
     * @return the fully configured {@link AsyncToolSpecification} ready for registration
     */
    private AsyncToolSpecification createToolCurrentTime() {
        Tool tool = new Tool("current_time",
                "[MCP Primitives:Tool] Returns the current server date and time in ISO-8601 format.",
                toJson(Map.of("type", "object", "properties", Map.of())));
        return new AsyncToolSpecification(tool, (exchange, arguments) -> {
            logger.info("Executing [MCP Primitives:Tool] 'current_time'");
            String now = Instant.now().toString();
            return Mono.just(new CallToolResult(List.of(new TextContent(now)), false));
        });
    }

    // -------------------------------------------------------------------------
    // Sampling factory method
    // (Registered as a tool but semantically performs LLM sampling via the host)
    // -------------------------------------------------------------------------

    /**
     * MCP Sampling — {@code llm_expand} (registered as a Tool).
     *
     * <p>
     * Demonstrates the MCP <em>Sampling</em> capability: the server initiates a text-generation request back to the
     * <em>client's</em> LLM rather than running any model itself. The flow is:
     * <ol>
     * <li>The client invokes this tool with a short {@code phrase}.</li>
     * <li>The server builds a {@link CreateMessageRequest} and calls {@code exchange.createMessage(...)}, which sends a
     * {@code sampling/createMessage} request back across the MCP connection to the client.</li>
     * <li>The client's LLM (which assumes the MCP Client is part of LLM providers such as IBM Bob, Claude Code, Cline, ...)
     * generates the expanded paragraph and returns it.</li>
     * <li>The server wraps the generated text in a {@link CallToolResult} and returns it to the original caller.</li>
     * </ol>
     *
     * <p>
     * Sampling is technically registered as a Tool because the MCP protocol has no dedicated "sampling primitive" from the
     * server's perspective — Sampling is a <em>capability</em> (a callback the server can make to the client), not a primitive
     * the server exposes. The {@code createSampling*()} naming convention makes this semantic distinction visible in the code
     * even though the SDK type is the same {@link AsyncToolSpecification}.
     *
     * @return the fully configured {@link AsyncToolSpecification} ready for registration
     */
    private AsyncToolSpecification createSamplingLlmExpand() {
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
     * Static informational resource that returns a short plain-text summary of this server: its name, the SDK it uses, and the
     * running JVM version. The JVM version is read at request time via {@link System#getProperty} so it always reflects the
     * actual runtime.
     *
     * @return the fully configured {@link AsyncResourceSpecification} ready for registration
     */
    private AsyncResourceSpecification createResourceInfo() {
        Resource resource = new Resource("mcp://poc/info", "Server Info",
                "[MCP Primitives:Resource] Return basic information about this MCP PoC server", "text/plain", null);
        return new AsyncResourceSpecification(resource, (exchange, request) -> {
            logger.info("Reading [MCP Primitives:Resource] 'mcp://poc/info'");
            return Mono.just(new ReadResourceResult(List.of(new TextResourceContents("mcp://poc/info", "text/plain",
                    "MCP PoC Server\nSDK : MCP Java SDK\nJava: " + System.getProperty("java.version")))));
        });
    }

    /**
     * MCP Resource — {@code mcp://poc/system-properties}.
     *
     * <p>
     * Returns all current JVM system properties serialised as a JSON object. Because {@link System#getProperties()} returns
     * arbitrary strings that may contain backslashes, double-quotes, and newlines, those characters are escaped manually before
     * embedding each value in the JSON string. The result is returned with MIME type {@code application/json}.
     *
     * <p>
     * The read handler propagates any unexpected exception as a reactive error via {@code Mono.error(e)} so the SDK can
     * translate it into a proper MCP error response rather than silently swallowing it.
     *
     * @return the fully configured {@link AsyncResourceSpecification} ready for registration
     */
    private AsyncResourceSpecification createResourceSystemProperties() {
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
     * MCP Resource — {@code mcp://poc/echo/Hello-From-Resource-Template}.
     *
     * <p>
     * A static resource whose URI intentionally conforms to the echo {@link ResourceTemplate} pattern
     * ({@code mcp://poc/echo/{message}}). Its purpose is discoverability: by pre-registering this concrete URI the server
     * exposes it via {@code resources/list}, allowing clients to find and read it without having to construct a URI themselves.
     *
     * <p>
     * The read handler extracts the final path segment from the requested URI and echoes it back as plain text. This is the
     * same logic used by {@link #createResourceEchoJunit()}, demonstrating that multiple static resources can share identical
     * handler logic while differing only in their registered URI.
     *
     * @return the fully configured {@link AsyncResourceSpecification} ready for registration
     */
    private AsyncResourceSpecification createResourceEchoHello() {
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
     * MCP Resource — {@code mcp://poc/echo/junit-test}.
     *
     * <p>
     * A static resource used by the JUnit integration test ({@code McpIntegrationTest}) to verify that the server correctly
     * handles {@code resources/read} requests. Like {@link #createResourceEchoHello()}, its URI conforms to the echo
     * {@link ResourceTemplate} pattern, and its read handler echoes back the final path segment as plain text.
     *
     * @return the fully configured {@link AsyncResourceSpecification} ready for registration
     */
    private AsyncResourceSpecification createResourceEchoJunit() {
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
     * Advertises to clients that this server understands URIs matching the pattern {@code mcp://poc/echo/{message}}, where
     * {@code {message}} is any string value. This advertisement appears in the {@code resourceTemplates/list} response.
     *
     * <p>
     * <strong>Important:</strong> a {@link ResourceTemplate} is metadata only — it carries no read handler. The SDK routes
     * {@code resources/read} requests by exact URI lookup in the registered {@link AsyncResourceSpecification} map, not by
     * template pattern matching. In SDK 0.9.0 only the two pre-registered concrete URIs ({@code Hello-From-Resource-Template}
     * and {@code junit-test}) can actually be read. Any other URI matching this pattern would result in a "resource not found"
     * error from the SDK.
     *
     * @return the {@link ResourceTemplate} metadata object ready for registration
     */
    private ResourceTemplate createResourceTemplateEcho() {
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
     * Returns a two-message prompt that instructs the LLM to review a code snippet. The prompt handler accepts two required
     * arguments:
     * <ul>
     * <li>{@code language} — the programming language (e.g. {@code Java})</li>
     * <li>{@code code} — the source code to review</li>
     * </ul>
     * The handler builds a system-role message that sets the reviewer persona and a user-role message that wraps the code in a
     * fenced code block. Both messages are returned as {@link Role#USER} because the MCP Prompt primitive only defines message
     * content — it is the client's responsibility to assign system vs. user roles when forwarding the messages to its LLM.
     *
     * <p>
     * If the {@code arguments} map is {@code null} (the client sent no arguments), sensible defaults ({@code "unknown"} / empty
     * string) are used so the handler never throws a {@code NullPointerException}.
     *
     * @return the fully configured {@link AsyncPromptSpecification} ready for registration
     */
    private AsyncPromptSpecification createPromptCodeReview() {
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
     * Returns a single-message prompt that asks the LLM to summarise a block of text into a given number of bullet points.
     * Accepts two arguments:
     * <ul>
     * <li>{@code text} (required) — the text to summarise</li>
     * <li>{@code points} (optional, default {@code "5"}) — number of bullet points</li>
     * </ul>
     * The optional {@code points} argument defaults to {@code "5"} when absent, which is handled defensively: both a
     * {@code null} map and a missing key produce the default via {@code getOrDefault}.
     *
     * @return the fully configured {@link AsyncPromptSpecification} ready for registration
     */
    private AsyncPromptSpecification createPromptSummarise() {
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
     * 
     * @param map to convert
     * @return {@link String}
     */
    private String toJson(final Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new RuntimeException("Error converting schema to JSON string", e);
        }
    }

}
