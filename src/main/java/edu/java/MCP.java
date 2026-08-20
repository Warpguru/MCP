package edu.java;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import edu.java.service.SseServer;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Tutorial for using the <a href="https://github.com/modelcontextprotocol/java-sdk">MCP Java SDK</a> for implementing a MCP
 * StdioServer and MCP Client.
 */
public class MCP {

    /** Default logger (using appender that includes e.g. timestamp, ...). */
    private static final Logger logger = LogManager.getLogger(MCP.class);

    /** MCP version (keep in sync with pom.xml). */
    public static final String MCP_VERSION = "1.0.0";

    public static final String MCP_JAVA_SDK_STDIO_SERVER = "MCP Java SDK StdioServer";
    public static final String MCP_JAVA_SDK_SSE_SERVER = "MCP Java SDK SseServer";
    public static final String MCP_JAVA_SDK_CLIENT = "MCP Java SDK Client";

    public static void main(String[] args) {
        // Check for requested MCP function
        if (args.length > 0) {
            String subCommand = args[0].toLowerCase();
            String[] subArgs = java.util.Arrays.copyOfRange(args, 1, args.length);

            if ("stdioserver".equalsIgnoreCase(subCommand)) {
                edu.java.service.StdioServer.main(subArgs);
                return;
            } else if ("sseserver".equalsIgnoreCase(subCommand)) {
                edu.java.service.SseServer.main(subArgs);
                return;
            } else if ("client".equalsIgnoreCase(subCommand)) {
                edu.java.service.Client.main(subArgs);
                return;
            }
        }

        // Default behavior: Show usage information
        System.out.println("=========================================================================");
        System.out.println("                     MCP Java SDK Tutorial Launcher                     ");
        System.out.println("=========================================================================");
        System.out.println("");
        System.out.println("MCP Java SDK Api Loaded:");
        McpSchema.Implementation implementation = new McpSchema.Implementation("MCP Stdio/Sse Reference (Java SDK)", "0.9.0");
        System.out.println("  Name:    " + implementation.name());
        System.out.println("  Version: " + implementation.version());
        logger.info("MCP Java SDK Api: Name = {}, Version = {}", implementation.name(), implementation.version());
        System.out.println("");
        System.out.println("Usage:");
        System.out.println("  java -jar target/MCP-" + MCP.MCP_VERSION + ".jar <subcommand> [args...]");
        System.out.println("");
        System.out.println("Available Subcommands:");
        System.out.println("  stdioserver");
        System.out.println("    -> Launches the MCP Server over standard input/output (stdio).");
        System.out.println("       Must be spawned as a child process by an MCP Host (no arguments).");
        System.out.println("");
        System.out.println("  sseserver");
        System.out.println("    -> Launches the stand-alone MCP Server over Server-Sent Events (SSE).");
        System.out.println("       Binds strictly to local loopback interface " + SseServer.SSE_HOST + ":" + SseServer.SSE_PORT
                + " (no arguments).");
        System.out.println("");
        System.out.println("  client [sse] [url]   -- OR --   client [command] [args...]");
        System.out.println("    -> Launches the Universal MCP Client in one of three modes:");
        System.out.println("");
        System.out.println("    * Mode A: Internal Loopback (Omit all arguments)");
        System.out.println("      Command:  client");
        System.out.println("      Behavior: Spawns the StdioServer internally and executes test handshake.");
        System.out.println("");
        System.out.println("    * Mode B: Remote SSE Connection (Using literal keyword 'sse')");
        System.out.println("      Command:  client sse [url]");
        System.out.println("      Parameters:");
        System.out.println("        - sse: [REQUIRED] Literal keyword to select SSE transport.");
        System.out.println("        - url: [OPTIONAL] Base URL of the server. Defaults to '" + SseServer.SSE_SERVER + "'.");
        System.out.println("");
        System.out.println("    * Mode C: Custom Stdio Subprocess (Using a command to launch)");
        System.out.println("      Command:  client <command> [args...]");
        System.out.println("      Parameters:");
        System.out.println("        - command: [REQUIRED] Path or executable to spawn (e.g. 'node').");
        System.out.println("        - args...: [OPTIONAL] Parameters forwarded to the spawned program.");
        System.out.println("=========================================================================");
    }

}
