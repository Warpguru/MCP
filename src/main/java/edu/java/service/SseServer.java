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
import io.modelcontextprotocol.server.transport.WebFluxSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

/**
 * MCP Server implementation over HTTP + SSE (Server-Sent Events) using WebFlux transport. Inherits all core MCP primitive
 * factory methods from {@link Server}.
 */
public class SseServer extends Server {

    /** IP address (typically 127.0.0.1) of MCP Server. */
    public static final String SSE_HOST = "127.0.0.1";
    /** Port of MCP Server. */
    public static final String SSE_PORT = "8080";
    /** Adress of MCP Server. */
    public static final String SSE_SERVER = "http" + "://" + SSE_HOST + ":" + SSE_PORT;
    /** SSE stream subscription endpoint path. */
    public static final String SSE_ENDPOINT = "/sse";
    /** Client JSON-RPC command message endpoint path. */
    public static final String MESSAGE_ENDPOINT = "/message";

    /**
     * Constructor initializing the SseServer base details.
     */
    public SseServer() {
        super("SseServer", "SseServer Info");
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
        new SseServer().processTransportSse();
    }

    // -------------------------------------------------------------------------
    // Transport entry points
    // -------------------------------------------------------------------------

    /**
     * MCP Transport — Server-Sent Events (SSE).
     *
     * <p>
     * Launches the MCP Server over the SSE transport, setting up standard HTTP endpoints for client routing. Unlike stdio which
     * communicates over a single subprocess pipe, this transport uses a standalone reactive Netty HTTP server bound strictly to
     * the loopback interface {@link #SSE_HOST} and {@link #SSE_PORT}.
     *
     * <p>
     * Delegates all primitive registration, endpoint routing, and server startup to {@link #buildServer()}. Catch block
     * guarantees any fatal startup exceptions are logged cleanly and shuts down the process with exit code {@code 1}.
     */
    public void processTransportSse() {
        loggerSysout.info("Starting {} over sse transport...", MCP.MCP_JAVA_SDK_SSE_SERVER);
        try {
            buildServer();
        } catch (Exception e) {
            loggerSysout.error("Fatal error starting {}: {}", MCP.MCP_JAVA_SDK_SSE_SERVER, e.getMessage());
            System.exit(1);
        }
    }

    // -------------------------------------------------------------------------
    // Transport-agnostic server builder
    // -------------------------------------------------------------------------

    /**
     * Registers all MCP primitives and starts the Server-Sent Events (SSE) server.
     *
     * <p>
     * Initializes the WebFlux SSE transport, registers server-side capabilities, definitions, and specifications (Tools,
     * Resources, and Prompts), and spins up a standalone Netty HTTP server listening on the configured loopback endpoint
     * {@link #SSE_SERVER}.
     *
     * <p>
     * Blocks the main thread forever using {@code Mono.never()} to let active background reactive Netty handler threads manage
     * simultaneous network streams indefinitely.
     */
    private void buildServer() {
        try {
            // 1. Initialize the WebFlux SSE Transport Provider
            //@formatter:off
            WebFluxSseServerTransportProvider transportProvider = WebFluxSseServerTransportProvider.builder()
                    .jsonMapper(McpJsonDefaults.getMapper())
                    .sseEndpoint(SSE_ENDPOINT)
                    .messageEndpoint(MESSAGE_ENDPOINT).build();
            //@formatter:on

            // 2. Build Server Capabilities (Tools, Resources, Prompts)
            ServerCapabilities capabilities = ServerCapabilities.builder().tools(true).resources(false, true).prompts(true)
                    .build();

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
                    .serverInfo(MCP.MCP_JAVA_SDK_SSE_SERVER, MCP.MCP_VERSION)
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
                    .host(SSE_HOST) // Security constraint: never bind to 0.0.0.0
                    .port(Integer.valueOf(SSE_PORT))
                    .handle(adapter)
                    .bindNow();
            //@formatter:on

            loggerSysout.info("{} started successfully and listening on {}", MCP.MCP_JAVA_SDK_SSE_SERVER, SSE_SERVER);
            loggerSysout.info("  SSE Endpoint: {}", SSE_SERVER + SSE_ENDPOINT);
            loggerSysout.info("  Message Endpoint: {}", SSE_SERVER + MESSAGE_ENDPOINT);

            // Keep the main thread alive indefinitely to let background netty threads run
            Mono.never().block();
        } catch (Exception e) {
            loggerSysout.error("Fatal error starting {}: {}", MCP.MCP_JAVA_SDK_SSE_SERVER, e.getMessage());
            throw new RuntimeException(e);
        }
    }

}
