package edu.java.service;

import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.server.RouterFunctions;

import edu.java.MCP;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncPromptSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncResourceSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncResourceTemplateSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.server.transport.WebFluxStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

/**
 * MCP Server implementation over HTTP Streamable transport (MCP Spec 2025-03-26) using WebFlux. Inherits all core MCP primitive
 * factory methods from {@link Server}.
 *
 * <p>
 * Streamable HTTP is the modern replacement for the legacy SSE transport. It condenses both the SSE notification channel and
 * the JSON-RPC command channel into a single {@code /mcp} endpoint that supports GET (open an SSE notification stream) and POST
 * (send a JSON-RPC message, receive an optional SSE stream or direct JSON response in the same HTTP response).
 */
public class StreamableSseServer extends Server {

    /** IP address (typically 127.0.0.1) of MCP Streamable HTTP Server. */
    public static final String STREAMABLE_HOST = "127.0.0.1";
    /** Port of MCP Streamable HTTP Server. */
    public static final String STREAMABLE_PORT = "8081";
    /** Base address of MCP Streamable HTTP Server. */
    public static final String STREAMABLE_SERVER = "http" + "://" + STREAMABLE_HOST + ":" + STREAMABLE_PORT;
    /** Unified MCP Streamable HTTP endpoint path (handles both GET and POST). */
    public static final String STREAMABLE_ENDPOINT = "/mcp";

    /**
     * Constructor initializing the StreamableSseServer base details.
     */
    public StreamableSseServer() {
        super("StreamableSseServer", "StreamableSseServer Info");
    }

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    /**
     * Application entry point.
     *
     * @param args command-line arguments (not used)
     */
    public static void main(String[] args) {
        new StreamableSseServer().processTransportStreamable();
    }

    // -------------------------------------------------------------------------
    // Transport entry points
    // -------------------------------------------------------------------------

    /**
     * MCP Transport — Streamable HTTP (MCP Spec 2025-03-26).
     *
     * <p>
     * Launches the MCP Server over the Streamable HTTP transport. Unlike the legacy SSE transport which required two separate
     * endpoints ({@code /sse} and {@code /message}), this transport exposes a single unified endpoint
     * {@link #STREAMABLE_ENDPOINT} that handles both GET requests (open a persistent SSE notification stream) and POST requests
     * (send a JSON-RPC message and receive a response inline or via SSE).
     *
     * <p>
     * The server binds strictly to the loopback interface {@link #STREAMABLE_HOST} and {@link #STREAMABLE_PORT}. Delegates all
     * primitive registration, endpoint routing, and server startup to {@link #buildServer()}. Any fatal startup exception is
     * logged cleanly and the process shuts down with exit code {@code 1}.
     */
    public void processTransportStreamable() {
        loggerSysout.info("Starting {} over streamable-http transport...", MCP.MCP_JAVA_SDK_STREAMABLE_SERVER);
        try {
            buildServer();
        } catch (Exception e) {
            loggerSysout.error("Fatal error starting {}: {}", MCP.MCP_JAVA_SDK_STREAMABLE_SERVER, e.getMessage());
            System.exit(1);
        }
    }

    // -------------------------------------------------------------------------
    // Transport-agnostic server builder
    // -------------------------------------------------------------------------

    /**
     * Registers all MCP primitives and starts the Streamable HTTP server.
     *
     * <p>
     * Initializes the WebFlux Streamable HTTP transport provider, registers server-side capabilities, definitions, and
     * specifications (Tools, Resources, and Prompts), and spins up a standalone Netty HTTP server listening on the configured
     * loopback endpoint {@link #STREAMABLE_SERVER}.
     *
     * <p>
     * Blocks the main thread forever using {@code Mono.never()} to let active background reactive Netty handler threads manage
     * simultaneous network streams indefinitely.
     */
    private void buildServer() {
        try {
            // 1. Initialize the WebFlux Streamable HTTP Transport Provider
            //@formatter:off
            WebFluxStreamableServerTransportProvider transportProvider = WebFluxStreamableServerTransportProvider
                    .builder()
                    .jsonMapper(McpJsonDefaults.getMapper())
                    .messageEndpoint(STREAMABLE_ENDPOINT)
                    .build();
            //@formatter:on

            // 2. Build Server Capabilities (Tools, Resources, Prompts)
            //@formatter:off
            ServerCapabilities capabilities = ServerCapabilities
                    .builder()
                    .tools(true)
                    .resources(false, true)
                    .prompts(true)
                    .build();
            //@formatter:on

            // 3. Define and configure all specifications
            AsyncToolSpecification toolEcho = createToolEcho();
            AsyncToolSpecification toolAdd = createToolAdd();
            AsyncToolSpecification toolCurrentTime = createToolCurrentTime();
            AsyncToolSpecification toolLlmExpand = createSamplingLlmExpand();

            AsyncResourceSpecification resourceInfo = createResourceInfo();
            AsyncResourceSpecification resourceSystemProperties = createResourceSystemProperties();
            AsyncResourceSpecification resourceEchoHello = createResourceEchoHello();
            AsyncResourceSpecification resourceEchoJunit = createResourceEchoJunit();

            AsyncResourceTemplateSpecification resourceTemplateEcho = createResourceTemplateEcho();

            AsyncPromptSpecification promptCodeReview = createPromptCodeReview();
            AsyncPromptSpecification promptSummarise = createPromptSummarise();

            // 4. Build and configure the McpAsyncServer
            //@formatter:off
            @SuppressWarnings("unused")
            McpAsyncServer server = McpServer
                    .async(transportProvider)
                    .serverInfo(MCP.MCP_JAVA_SDK_STREAMABLE_SERVER, MCP.MCP_VERSION)
                    .capabilities(capabilities)
                    .tools(toolEcho, toolAdd, toolCurrentTime, toolLlmExpand)
                    .resources(resourceInfo, resourceSystemProperties, resourceEchoHello, resourceEchoJunit)
                    .resourceTemplates(resourceTemplateEcho)
                    .prompts(promptCodeReview, promptSummarise)
                    .build();
            //@formatter:on

            // 5. Wire the transport into a Reactor Netty HTTP server bound to 127.0.0.1 (Localhost only)
            var routerFunction = transportProvider.getRouterFunction();
            var httpHandler = RouterFunctions.toHttpHandler(routerFunction);
            var adapter = new ReactorHttpHandlerAdapter(httpHandler);

            // Run the MCP Server
            //@formatter:off
            HttpServer.create()
                    .host(STREAMABLE_HOST) // Security constraint: never bind to 0.0.0.0
                    .port(Integer.valueOf(STREAMABLE_PORT))
                    .handle(adapter)
                    .bindNow();
            //@formatter:on

            loggerSysout.info("{} started successfully and listening on {}", MCP.MCP_JAVA_SDK_STREAMABLE_SERVER,
                    STREAMABLE_SERVER);
            loggerSysout.info("  Streamable HTTP Endpoint: {}", STREAMABLE_SERVER + STREAMABLE_ENDPOINT);

            // Keep the main thread alive indefinitely to let background netty threads run
            Mono.never().block();
        } catch (Exception e) {
            loggerSysout.error("Fatal error starting {}: {}", MCP.MCP_JAVA_SDK_STREAMABLE_SERVER, e.getMessage());
            throw new RuntimeException(e);
        }
    }

}
