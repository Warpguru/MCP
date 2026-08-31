package edu.java.service;

import edu.java.MCP;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncPromptSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncResourceSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncResourceTemplateSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import reactor.core.publisher.Sinks;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * MCP StdioServer implementation using the <a href="https://github.com/modelcontextprotocol/java-sdk">MCP Java SDK</a>.
 * Inherits all core MCP primitive factory methods from {@link Server}.
 *
 * <p>
 * When using the stdio transport the server must never write to {@code System.out} directly; all diagnostic output goes through
 * the logger, which is configured to write to {@code System.err} and the log file.
 *
 * <h2>Process lifecycle and orphan prevention</h2>
 * <p>
 * A Stdio MCP server is always launched as a <em>child process</em> by the host (e.g. an IDE plugin). The MCP specification
 * does not define a {@code shutdown} notification for the Stdio transport — the host is solely responsible for terminating the
 * child process, either by closing the stdin pipe (which causes an EOF) or by sending an OS-level termination signal.
 *
 * <p>
 * Some hosts (e.g. IBM Bob as of 2026) close the MCP session internally when the user disables the server but do <em>not</em>
 * close the stdin pipe and do <em>not</em> OS-kill the process. Without a mitigation the Java process would sit indefinitely on
 * the blocking call at the end of {@link #processTransportStdio()}, resulting in orphaned {@code java.exe} processes visible in
 * the OS task manager. If the server itself spawns child processes (e.g. via
 * {@link io.modelcontextprotocol.client.transport.StdioClientTransport} for sampling), those grand-child processes are also
 * orphaned because their parent never closes their stdin pipe.
 *
 * <p>
 * This class addresses the problem with two complementary mechanisms that together cover all termination paths:
 * <ol>
 * <li><b>EOF-detecting stdin wrapper</b> — the {@link StdioServerTransportProvider} is constructed with an {@link InputStream}
 * wrapper around {@code System.in}. The wrapper delegates all reads to the real stream; when {@code read()} returns {@code -1}
 * (EOF — the host closed the pipe) it emits on a {@link Sinks.One} stop signal, which unblocks the main thread and allows the
 * JVM to exit naturally.</li>
 * <li><b>JVM shutdown hook</b> — a shutdown hook is registered before the main thread parks. It fires when the JVM begins
 * shutdown due to an OS-level {@code SIGTERM} / {@code TerminateProcess} call (i.e. the host kills the process directly rather
 * than closing the pipe). It emits on the same stop signal and logs the event.</li>
 * </ol>
 *
 * <p>
 * <b>Known limitation:</b> If the host closes the MCP session but does NOT close the stdin pipe and does NOT OS-kill the
 * process (observed with IBM Bob as of 2026-08), neither mechanism fires and the process remains alive until the host is
 * restarted or the user manually terminates it. This is a host-side gap, not a server bug. See the README section <em>"Process
 * Lifecycle &amp; Orphan Prevention"</em> for a full explanation.
 */
public class StdioServer extends Server {

    /**
     * Constructor initializing the StdioServer base details.
     */
    public StdioServer() {
        super("StdioServer", "MCP Stdio Server Info");
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
        new StdioServer().processTransportStdio();
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
     * A one-shot {@link Sinks.One} stop signal is created. The transport is given an EOF-detecting {@link InputStream} wrapper
     * around {@code System.in} that emits on the signal when EOF is reached. A JVM shutdown hook is also registered to emit on
     * the signal for OS-level termination. The method then blocks on the signal until either path fires.
     */
    public void processTransportStdio() {
        logger.info("Starting {} over stdio transport...", MCP.MCP_JAVA_SDK_STDIO_SERVER);
        try {
            // One-shot signal: unblocks the main thread when either EOF or OS termination is detected.
            Sinks.One<Void> stopSignal = Sinks.one();
            // Register the shutdown hook BEFORE constructing the transport and blocking.
            // This hook covers OS-level termination (SIGTERM / TerminateProcess) where the
            // host kills the child process directly rather than closing the stdin pipe first.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("{} JVM shutdown hook triggered — process is terminating.", MCP.MCP_JAVA_SDK_STDIO_SERVER);
                stopSignal.tryEmitEmpty();
            }, "stdio-shutdown-hook"));
            // Wrap System.in with an EOF detector.
            // When the SDK's StdioServerTransportProvider background thread reads EOF from stdin,
            // this wrapper emits on the stop signal, unblocking the main thread below.
            // The wrapper is otherwise transparent — every byte is forwarded to the real stream.
            InputStream eofDetectingStdin = new FilterInputStream(System.in) {

                @Override
                public int read() throws IOException {
                    int b = super.read();
                    if (b == -1) {
                        onEof();
                    }
                    return b;
                }

                @Override
                public int read(byte[] buf, int off, int len) throws IOException {
                    int bytes = super.read(buf, off, len);
                    if (bytes == -1) {
                        onEof();
                    }
                    return bytes;
                }

                private void onEof() {
                    logger.info("{} stdin EOF detected — initiating clean shutdown.", MCP.MCP_JAVA_SDK_STDIO_SERVER);
                    stopSignal.tryEmitEmpty();
                }
            };
            buildServer(new StdioServerTransportProvider(McpJsonDefaults.getMapper(), eofDetectingStdin, System.out));

            // Block the main thread until the stop signal fires (stdin EOF or OS kill).
            stopSignal.asMono().block();
            logger.info("{} main thread unblocked — exiting.", MCP.MCP_JAVA_SDK_STDIO_SERVER);
            System.exit(0);
        } catch (Exception e) {
            logger.error("Fatal error starting {}: {}", MCP.MCP_JAVA_SDK_STDIO_SERVER, e.getMessage());
            System.exit(1);
        }
    }

    // -------------------------------------------------------------------------
    // Transport-agnostic server builder
    // -------------------------------------------------------------------------

    /**
     * Registers all MCP primitives and starts the server on the given transport.
     *
     * <p>
     * This method only configures and starts the server. It does not block; lifecycle management (blocking and clean exit) is
     * the responsibility of the calling transport method.
     *
     * @param transportProvider the configured transport to attach the server to
     */
    private void buildServer(final McpServerTransportProvider transportProvider) {
        //@formatter:off
        ServerCapabilities capabilities = ServerCapabilities
                .builder()
                .tools(true)
                .resources(false, true)
                .prompts(true)
                .build();
        //@formatter:on

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
        AsyncResourceTemplateSpecification resourceTemplateEcho = createResourceTemplateEcho();

        // MCP Prompts
        AsyncPromptSpecification promptCodeReview = createPromptCodeReview();
        AsyncPromptSpecification promptSummarise = createPromptSummarise();

        //@formatter:off
        @SuppressWarnings("unused")
        McpAsyncServer server = McpServer
            .async(transportProvider)
            .serverInfo(McpSchema.Implementation.builder(MCP.MCP_JAVA_SDK_STDIO_SERVER, MCP.MCP_VERSION)
                    .title("MCP Java SDK \u2014 Stdio Reference Server")
                    .description("MCP Java SDK reference implementation over stdio transport. "
                            + "Exposes echo/add/time tools, static resources, a resource template, "
                            + "code-review and summarise prompts, and an LLM-expansion sampling capability.")
                    .build())
            .capabilities(capabilities)
            .tools(toolEcho, toolAdd, toolCurrentTime, toolLlmExpand)
            .resources(resourceInfo, resourceSystemProperties, resourceEchoHello, resourceEchoJunit)
            .resourceTemplates(resourceTemplateEcho)
            .prompts(promptCodeReview, promptSummarise)
            .build();
        //@formatter:on

        logger.info("{} started successfully.", MCP.MCP_JAVA_SDK_STDIO_SERVER);
    }

}
