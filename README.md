# Model Context Protocol (MCP) Java Tutorial

Welcome to the **MCP Java SDK Tutorial** repository! This project serves as a comprehensive reference implementation and step-by-step guide for developers looking to master the official [Model Context Protocol (MCP) Java SDK](https://github.com/modelcontextprotocol/java-sdk).

---

## Summary

The primary objective of this project is to provide a complete, hands-on tutorial for building, running, and diagnosing Model Context Protocol (MCP) components in a native Java environment. The project explores the full potential of the official Java MCP SDK through two primary deliverables:

1. **A Compliant MCP Server**:
   A robust, standard-compliant MCP server that exposes the four core MCP primitives: **Tools**, **Resources**, **Prompts**, and **Sampling**. The tutorial guides you through wrapping these primitives in standard I/O (stdio) or streamable HTTP/SSE transports, demonstrating how LLMs and host clients can securely interoperate with Java-based backend services.

2. **A Universal MCP Client (Feature Dumper)**:
   A native Java MCP client designed to connect to the tutorial's server, or **any other compliant MCP server**. Once connected, this client queries and dumps all available capabilities, tools, resources, and prompts exposed by the target server into a clean, structured diagnostic report. This "Feature Dumper" client acts as an essential inspection tool, similar to the Node-based MCP Inspector, but fully native to Java.

### Key Learnings & Stack

This tutorial implements the design principles and learnings from [`doc/Research.md`](doc/Research.md):
* **Modern Java Platform**: Leverages the power of **Java 21** (virtual threads, records, and pattern matching) and **Maven**.
* **Reactive Core**: Utilizes Project Reactor Netty for seamless reactive streams and SSE network communication.
* **Production-Grade Logging**: Configured with Log4j2 and SLF4J to support clean, non-conflicting diagnostics (especially when using standard I/O transport pipelines where system stdout is reserved for JSON-RPC payloads).
* **Single-POM Versioning**: Centralized dependency properties for robust and reproducible builds.

---

## Current Status & Getting Started

### Prerequisites

Ensure your local environment is configured to execute:
* Node (when using [MCP Inspector](https://modelcontextprotocol.io/docs/2026-07-28/tools/inspector/web))
* Git
* Maven
* JDK21

### Build the Project

Build the executable fat-JAR:
```bash
mvn clean package
```

### Verify the Installation

Run the packaged PoC executable to verify that the environment and core MCP classes load successfully:

```bash
java -jar target/MCP-1.0.0.jar
```

This will output the name and version of the loaded MCP schema implementation and log the diagnostics to `MCP.log`.

### Test with MCP Inspector

```bash
npx @modelcontextprotocol/inspector@latest java -cp target/MCP-1.0.0.jar edu.java.service.Server
```
