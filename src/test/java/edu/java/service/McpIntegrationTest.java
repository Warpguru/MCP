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
    
}
