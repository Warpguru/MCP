package edu.java.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import edu.java.MCP;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncPromptSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncResourceSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.server.transport.WebFluxSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.server.RouterFunctions;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

/**
 * MCP Server implementation over HTTP + SSE (Server-Sent Events) using WebFlux transport.
 * Inherits all core MCP primitive factory methods from {@link Server}.
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

    /** Default logger (using appender that includes e.g. timestamp, ...). */
    private static final Logger logger = LogManager.getLogger(SseServer.class);

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
        try {
            new SseServer().processTransportSse();
        } catch (Exception e) {
            logger.error("Fatal error starting " + MCP.MCP_JAVA_SDK_SSE_SERVER, e);
            System.err.println("Fatal error: " + e.getMessage());
            System.exit(1);
        }
    }

    // -------------------------------------------------------------------------
    // Transport entry points
    // -------------------------------------------------------------------------

    public void processTransportSse() {
        System.err.println("Starting " + MCP.MCP_JAVA_SDK_SSE_SERVER + " over sse transport...");
        logger.info("Starting " + MCP.MCP_JAVA_SDK_SSE_SERVER + " over sse transport...");
        buildServer();
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
                    .objectMapper(objectMapper)
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

            ResourceTemplate resourceTemplateEcho = createResourceTemplateEcho();

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

            System.err.println(MCP.MCP_JAVA_SDK_SSE_SERVER + " started successfully and listening on " + SSE_SERVER);
            System.err.println("  SSE Endpoint: " + SSE_SERVER + SSE_ENDPOINT);
            System.err.println("  Message Endpoint: " + SSE_SERVER + MESSAGE_ENDPOINT);
            logger.info(MCP.MCP_JAVA_SDK_SSE_SERVER + " started successfully and listening on " + SSE_SERVER + SSE_ENDPOINT);

            // Keep the main thread alive indefinitely to let background netty threads run
            Mono.never().block();

        } catch (Exception e) {
            logger.error("Fatal error starting " + MCP.MCP_JAVA_SDK_SSE_SERVER, e);
            throw new RuntimeException(e);
        }
    }

}
