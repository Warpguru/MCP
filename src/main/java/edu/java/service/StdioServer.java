package edu.java.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import edu.java.MCP;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncPromptSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncResourceSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import reactor.core.publisher.Mono;

/**
 * MCP StdioServer implementation using the <a href="https://github.com/modelcontextprotocol/java-sdk">MCP Java SDK</a>.
 * Inherits all core MCP primitive factory methods from {@link Server}.
 *
 * <p>
 * When using the stdio transport the server must never write to {@code System.out} directly; all diagnostic output goes through
 * the logger, which is configured to write to {@code System.err} and the log file.
 */
public class StdioServer extends Server {

    /** Default logger (using appender that includes e.g. timestamp, ...). */
    private static final Logger logger = LogManager.getLogger(StdioServer.class);

    /**
     * Constructor initializing the StdioServer base details.
     */
    public StdioServer() {
        super("StdioServer", "StdioServer Info");
    }

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
            new StdioServer().processTransportStdio();
        } catch (Exception e) {
            logger.error("Fatal error starting " + MCP.MCP_JAVA_SDK_STDIO_SERVER, e);
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
        logger.info("Starting " + MCP.MCP_JAVA_SDK_STDIO_SERVER + " over stdio transport...");
        buildServer(new StdioServerTransportProvider());
    }

    // -------------------------------------------------------------------------
    // Transport-agnostic server builder
    // -------------------------------------------------------------------------

    /**
     * Registers all MCP primitives and starts the server on the given transport.
     *
     * <p>
     * After {@link McpAsyncServer} is built the method blocks the calling thread forever via {@code Mono.never()} so that the
     * reactive background threads that handle I/O keep running.
     *
     * @param transportProvider the configured transport to attach the server to
     */
    private void buildServer(final McpServerTransportProvider transportProvider) {
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
            .serverInfo(MCP.MCP_JAVA_SDK_STDIO_SERVER, MCP.MCP_VERSION)
            .capabilities(capabilities)
            .tools(toolEcho, toolAdd, toolCurrentTime, toolLlmExpand)
            .resources(resourceInfo, resourceSystemProperties, resourceEchoHello, resourceEchoJunit)
            .resourceTemplates(resourceTemplateEcho)
            .prompts(promptCodeReview, promptSummarise)
            .build();
        //@formatter:on
        
        logger.info(MCP.MCP_JAVA_SDK_STDIO_SERVER + " started successfully.");
        // Block the calling thread forever so the reactive transport threads keep running.
        Mono.never().block();
    }

}
