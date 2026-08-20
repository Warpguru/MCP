package edu.java.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * End-to-end integration test that verifies the full MCP server and client interaction.
 */
public class McpIntegrationTest {

    @Test
    public void testClientServerEndToEnd() {
        // Run the main Client class with no arguments.
        // This launches our Server as a child process, queries tools, resources, templates,
        // prompts, and runs tool executions and LLM sampling callbacks.
        assertDoesNotThrow(() -> {
            Client.main(new String[] {});
        }, "The end-to-end client-server interaction should complete without throwing any exception.");
    }

    @Test
    public void testSseClientServerEndToEnd() {
        // 1. Launch SseServer on a background daemon thread
        Thread serverThread = new Thread(() -> {
            try {
                SseServer.main(new String[] {});
            } catch (Exception e) {
                // Ignore background thread interruption / exit
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        // 2. Wait 3 seconds to ensure SseServer starts up and binds to port 8080
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 3. Run the Client in sse mode pointing to the dynamic server base URL
        assertDoesNotThrow(() -> {
            Client.main(new String[] { "sse", SseServer.SSE_SERVER });
        }, "The end-to-end SSE client-server interaction should complete without throwing any exception.");
    }

}
