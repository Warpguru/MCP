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
    @SuppressWarnings("unused")
    private static final Logger logger = LogManager.getLogger(MCP.class);

    /** Sysout logger (no formatting). */
    private static final Logger loggerSysout = LogManager.getLogger("edu.java.Sysout");

    /** MCP version (keep in sync with pom.xml). */
    public static final String MCP_VERSION = "1.0.0";

    /** MCP stdio server. */
    public static final String MCP_JAVA_SDK_STDIO_SERVER = "MCP Java SDK StdioServer";
    /** MCP sse server. */
    public static final String MCP_JAVA_SDK_SSE_SERVER = "MCP Java SDK SseServer";
    /** MCP client. */
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
        loggerSysout.info("=========================================================================");
        loggerSysout.info("                     MCP Java SDK Tutorial Launcher                     ");
        loggerSysout.info("=========================================================================");
        loggerSysout.info("");
        McpSchema.Implementation implementation = new McpSchema.Implementation("MCP Stdio/Sse Reference (Java SDK)", "0.9.0");
        loggerSysout.info("MCP Java SDK Api: Name = {}, Version = {}", implementation.name(), implementation.version());
        loggerSysout.info("");
        loggerSysout.info("Usage:");
        loggerSysout.info("  java -jar target/MCP-" + MCP.MCP_VERSION + ".jar <subcommand> [args...]");
        loggerSysout.info("");
        loggerSysout.info("Available Subcommands:");
        loggerSysout.info("  stdioserver");
        loggerSysout.info("    -> Launches the MCP Server over standard input/output (stdio).");
        loggerSysout.info("       Must be spawned as a child process by an MCP Host (no arguments).");
        loggerSysout.info("");
        loggerSysout.info("  sseserver");
        loggerSysout.info("    -> Launches the stand-alone MCP Server over Server-Sent Events (SSE).");
        loggerSysout.info("       Binds strictly to local loopback interface " + SseServer.SSE_HOST + ":" + SseServer.SSE_PORT
                + " (no arguments).");
        loggerSysout.info("");
        loggerSysout.info("  client [sse] [url]   -- OR --   client [command] [args...]");
        loggerSysout.info("    -> Launches the Universal MCP Client in one of three modes:");
        loggerSysout.info("");
        loggerSysout.info("    * Mode A: Internal Loopback (Omit all arguments)");
        loggerSysout.info("      Command:  client");
        loggerSysout.info("      Behavior: Spawns the StdioServer internally and executes test handshake.");
        loggerSysout.info("");
        loggerSysout.info("    * Mode B: Remote SSE Connection (Using literal keyword 'sse')");
        loggerSysout.info("      Command:  client sse [url]");
        loggerSysout.info("      Parameters:");
        loggerSysout.info("        - sse: [REQUIRED] Literal keyword to select SSE transport.");
        loggerSysout.info("        - url: [OPTIONAL] Base URL of the server. Defaults to '{}'.", SseServer.SSE_SERVER);
        loggerSysout.info("");
        loggerSysout.info("    * Mode C: Custom Stdio Subprocess (Using a command to launch)");
        loggerSysout.info("      Command:  client <command> [args...]");
        loggerSysout.info("      Parameters:");
        loggerSysout.info("        - command: [REQUIRED] Path or executable to spawn (e.g. 'node').");
        loggerSysout.info("        - args...: [OPTIONAL] Parameters forwarded to the spawned program.");
        loggerSysout.info("=========================================================================");
    }

}
