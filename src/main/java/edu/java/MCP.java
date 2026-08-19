package edu.java;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Tutorial for using the <a href="https://github.com/modelcontextprotocol/java-sdk">MCP Java SDK</a> for implementing a MCP
 * Server and MCP Client.
 */
public class MCP {

    /** Default logger (using appender that includes e.g. timestamp, ...). */
    private static final Logger logger = LogManager.getLogger(MCP.class);

    /** MCP version (keep in sync with pom.xml). */
    public static final String MCP_VERSION = "1.0.0";

    public static final String MCP_JAVA_SDK_SERVER = "MCP Java SDK Server";
    public static final String MCP_JAVA_SDK_CLIENT = "MCP Java SDK Client";

    public static void main(String[] args) {
        // Check for requested MCP function
        if (args.length > 0) {
            String subCommand = args[0].toLowerCase();
            String[] subArgs = java.util.Arrays.copyOfRange(args, 1, args.length);

            if ("server".equalsIgnoreCase(subCommand)) {
                edu.java.service.Server.main(subArgs);
                return;
            } else if ("client".equalsIgnoreCase(subCommand)) {
                edu.java.service.Client.main(subArgs);
                return;
            }
        }

        // Default behavior: Show usage information
        System.out.println("=================================================");
        System.out.println("          MCP Java SDK Tutorial Launcher         ");
        System.out.println("=================================================");
        System.out.println("");
        System.out.println("MCP Java SDK Api:");
        // Improvements: Call a method provided by the MCP Java SDK
        McpSchema.Implementation implementation = new McpSchema.Implementation("MCP Server (Java SDK)", "0.9.0");
        // Let's print out information about the created MCP SDK Implementation instance
        System.out.println("  Name:    " + implementation.name());
        System.out.println("  Version: " + implementation.version());
        logger.info("MCP Java SDK Api: Name = {}, Version = {}", implementation.name(), implementation.version());
        System.out.println("");
        System.out.println("Usage:");
        System.out.println("  java -jar target/MCP-1.0.0.jar server");
        System.out.println("    -> Launches the MCP Server over stdio.");
        System.out.println("");
        System.out.println("  java -jar target/MCP-1.0.0.jar client [cmd] [args...]");
        System.out.println("    -> Launches the MCP Client to query capabilities.");
        System.out.println("       If [cmd] is omitted, it will automatically launch and query");
        System.out.println("       the tutorial server packaged inside this same JAR.");
        System.out.println("=================================================");
    }

}
