package edu.java;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.modelcontextprotocol.spec.McpSchema;

public class MCP {
    
    /** Default logger (using appender that includes e.g. timestamp, ...). */
    private static final Logger logger = LogManager.getLogger(MCP.class);

    public static void main(String[] args) {
        System.out.println("Hello World!");
        logger.info("Hello World!");

        // Improvements: Call a method provided by the MCP Java SDK
        McpSchema.Implementation impl = new McpSchema.Implementation("MCP-Java-SDK-PoC", "0.9.0");
        
        // Let's print out information about the created MCP SDK Implementation instance
        System.out.println("Calling MCP SDK API:");
        System.out.println("Implementation name: " + impl.name());
        System.out.println("Implementation version: " + impl.version());
        
        logger.info("Calling MCP SDK API: Name = {}, Version = {}", impl.name(), impl.version());
    }

}
