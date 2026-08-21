package edu.java.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * End-to-end integration test suite that exercises the full MCP protocol flow across all three
 * supported transports: stdio, HTTP+SSE, and Streamable HTTP.
 *
 * <h2>Test strategy</h2>
 * Each test instantiates a real MCP server and a real MCP client, performs a complete protocol
 * handshake, queries all registered primitives (Tools, Resources, Resource Templates, and Prompts),
 * executes demonstration tool calls (including the Sampling round-trip via {@code llm_expand}),
 * and verifies that the entire flow completes without throwing any exception.
 *
 * <h2>Server lifecycle</h2>
 * The three transports differ significantly in how the server process is managed:
 * <ul>
 *   <li><b>Stdio ({@link #testClientServerEndToEnd})</b> — The client itself spawns the server.
 *       {@link Client#main} with no arguments calls {@link StdioServerTransportProvider} internally,
 *       which starts the {@link StdioServer} as a <em>child subprocess</em> via
 *       {@code ProcessBuilder}. When the client closes its end of the pipe (at
 *       {@code client.closeGracefully()}), the child process terminates automatically because its
 *       stdin EOF causes the transport to shut down. No manual thread management is needed.</li>
 *   <li><b>SSE ({@link #testSseClientServerEndToEnd})</b> — The server must be running
 *       <em>before</em> the client connects, because the SSE transport relies on an existing TCP
 *       listener. The test starts {@link SseServer} on a background <em>daemon thread</em> so the
 *       JVM does not wait for it to finish. The server blocks internally on
 *       {@code Mono.never().block()} to keep Reactor Netty alive. Because the thread is a daemon,
 *       the JVM reclaims it when the last non-daemon thread (the test runner) exits — no explicit
 *       shutdown is needed. A 3-second sleep gives Reactor Netty time to bind to port 8080 before
 *       the client attempts its first HTTP GET {@code /sse}.</li>
 *   <li><b>Streamable HTTP ({@link #testStreamableClientServerEndToEnd})</b> — Identical lifecycle
 *       to the SSE case. {@link StreamableSseServer} is started as a background daemon thread
 *       blocking on {@code Mono.never().block()}, and is reclaimed when the JVM exits. A 3-second
 *       sleep allows Reactor Netty to bind to port 8081 before the client connects.</li>
 * </ul>
 *
 * <h2>Why no explicit server shutdown</h2>
 * Both the SSE and Streamable HTTP servers block indefinitely on {@code Mono.never().block()}.
 * Interrupting that block mid-test would race with active client operations and is not needed:
 * marking the server thread as a <em>daemon</em> is sufficient — the JVM tears it down silently
 * once all non-daemon test threads have finished.
 */
public class McpIntegrationTest {

    /**
     * Verifies end-to-end MCP communication over the <b>stdio transport</b>.
     *
     * <p>
     * Calling {@link Client#main} with no arguments activates <em>Mode A (Internal Loopback)</em>:
     * the client builds a {@code StdioClientTransport} that forks a new JVM subprocess running
     * {@link StdioServer} on the current classpath. The parent and child processes communicate
     * exclusively over the child's {@code stdin}/{@code stdout} using line-delimited JSON-RPC 2.0
     * packets. The child's {@code stderr} is piped to the parent logger so diagnostics remain
     * visible without polluting the protocol stream.
     *
     * <p>
     * The client performs the full MCP handshake, dumps all Tools (4), Resources (4), Resource
     * Templates (1) and Prompts (2), runs demonstration calls for {@code echo}, {@code add}, and
     * {@code current_time}, invokes the {@code llm_expand} Sampling round-trip (server calls back
     * to the client's mock LLM), and gracefully closes the connection. The child process terminates
     * automatically when its stdin is closed.
     */
    @Test
    public void testClientServerEndToEnd() {
        assertDoesNotThrow(() -> {
            Client.main(new String[] {});
        }, "The end-to-end stdio client-server interaction should complete without throwing any exception.");
    }

    /**
     * Verifies end-to-end MCP communication over the <b>HTTP + SSE transport</b>.
     *
     * <p>
     * The SSE transport requires a pre-running HTTP server, so the test starts {@link SseServer}
     * as a background <em>daemon thread</em> before the client connects:
     * <ol>
     *   <li>A daemon thread is created wrapping {@link SseServer#main}. Using a daemon thread
     *       ensures the JVM will not wait for the server to stop when the test suite finishes —
     *       the thread is reclaimed automatically on JVM exit.</li>
     *   <li>The server calls {@link SseServer#processTransportSse()}, which initializes the
     *       {@code WebFluxSseServerTransportProvider}, builds the {@code McpAsyncServer} with all
     *       11 MCP primitives, and starts a Reactor Netty HTTP server bound to
     *       {@code 127.0.0.1:8080}. The method then parks the daemon thread on
     *       {@code Mono.never().block()} so the Netty I/O threads continue handling requests.</li>
     *   <li>The test sleeps for 3 seconds to give Reactor Netty time to complete its bind and
     *       accept connections.</li>
     *   <li>The client connects using {@code Client.main(new String[]{"sse", SSE_SERVER})}, which
     *       activates <em>Mode B (Remote SSE)</em> and instantiates an
     *       {@code HttpClientSseClientTransport} pointing to {@code http://127.0.0.1:8080}. The
     *       client opens a persistent GET {@code /sse} stream to receive server-pushed packets, and
     *       sends JSON-RPC 2.0 commands via HTTP POST {@code /message}.</li>
     *   <li>The full protocol flow (handshake, tool/resource/prompt listing, demonstration calls,
     *       Sampling round-trip, graceful close) is executed identically to the stdio test.</li>
     * </ol>
     */
    @Test
    public void testSseClientServerEndToEnd() {
        // 1. Start SseServer on a background daemon thread.
        //    Daemon status ensures the JVM reclaims this thread after the last non-daemon
        //    (test runner) thread exits — no explicit shutdown step is required.
        Thread serverThread = new Thread(() -> {
            try {
                SseServer.main(new String[] {});
            } catch (Exception e) {
                // Server threads blocked on Mono.never() are interrupted with
                // a CancellationException when the daemon is reclaimed — that is expected.
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        // 2. Wait for Reactor Netty to bind to port 8080 before the client connects.
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 3. Connect the client in SSE mode and execute the full protocol verification.
        assertDoesNotThrow(() -> {
            Client.main(new String[] { "sse", SseServer.SSE_SERVER });
        }, "The end-to-end SSE client-server interaction should complete without throwing any exception.");
    }

    /**
     * Verifies end-to-end MCP communication over the <b>Streamable HTTP transport</b>
     * (MCP Spec 2025-03-26).
     *
     * <p>
     * Streamable HTTP is the modern replacement for the legacy SSE transport. Instead of the two
     * separate {@code /sse} (GET) and {@code /message} (POST) endpoints used by the SSE transport,
     * it exposes a single unified endpoint ({@code /mcp}) that handles both GET requests (open a
     * persistent SSE notification stream for server-pushed events) and POST requests (send a
     * JSON-RPC message and receive an optional SSE stream or direct JSON response inline).
     *
     * <p>
     * The test lifecycle mirrors {@link #testSseClientServerEndToEnd}:
     * <ol>
     *   <li>A daemon thread is created wrapping {@link StreamableSseServer#main}. Using a daemon
     *       thread ensures the JVM reclaims the server silently when the test suite finishes.</li>
     *   <li>The server calls {@link StreamableSseServer#processTransportStreamable()}, which
     *       initializes the {@code WebFluxStreamableServerTransportProvider}, builds the
     *       {@code McpAsyncServer} with all 11 MCP primitives, and starts a Reactor Netty HTTP
     *       server bound to {@code 127.0.0.1:8081}. The daemon thread is then parked on
     *       {@code Mono.never().block()} so background Netty I/O threads continue running.</li>
     *   <li>The test sleeps for 3 seconds to give Reactor Netty time to bind and become ready.</li>
     *   <li>The client connects using
     *       {@code Client.main(new String[]{"streamable", STREAMABLE_SERVER})}, activating
     *       <em>Mode C (Remote Streamable HTTP)</em>. This instantiates an
     *       {@code HttpClientStreamableHttpTransport} pointed at {@code http://127.0.0.1:8081/mcp}
     *       which uses a single endpoint for all JSON-RPC traffic.</li>
     *   <li>The full protocol flow (handshake, tool/resource/prompt listing, demonstration calls,
     *       Sampling round-trip, graceful close) is executed identically to the other two tests.</li>
     * </ol>
     */
    @Test
    public void testStreamableClientServerEndToEnd() {
        // 1. Start StreamableSseServer on a background daemon thread.
        //    Daemon status ensures the JVM reclaims this thread after the last non-daemon
        //    (test runner) thread exits — no explicit shutdown step is required.
        Thread serverThread = new Thread(() -> {
            try {
                StreamableSseServer.main(new String[] {});
            } catch (Exception e) {
                // Server threads blocked on Mono.never() are interrupted with
                // a CancellationException when the daemon is reclaimed — that is expected.
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        // 2. Wait for Reactor Netty to bind to port 8081 before the client connects.
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 3. Connect the client in streamable mode and execute the full protocol verification.
        assertDoesNotThrow(() -> {
            Client.main(new String[] { "streamable", StreamableSseServer.STREAMABLE_SERVER });
        }, "The end-to-end Streamable HTTP client-server interaction should complete without throwing any exception.");
    }

}
