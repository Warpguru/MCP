---
name: add-mcp-primitive
description: Use when the user wants to add a new MCP Tool, Resource, Resource Template, or Prompt to the Java MCP tutorial server. Guides through adding the factory method, registering it in all three server subclasses, verifying the build, and updating the documentation.
---

# Add a New MCP Primitive

Follow these steps in order whenever a new Tool, Resource, Resource Template, or Prompt is requested.

---

## Step 1 — Add the factory method to `Server.java`

All MCP primitives are defined as `protected` factory methods in
`src/main/java/edu/java/service/Server.java`. Add the new method in the correct section
(Tools, Resources, or Prompts) following the naming convention exactly:

| Primitive type | Method prefix | Return type | Example |
|---|---|---|---|
| Tool | `createTool*()` | `AsyncToolSpecification` | `createToolWeather()` |
| Sampling-backed Tool | `createSampling*()` | `AsyncToolSpecification` | `createSamplingLlmSummarise()` |
| Resource (concrete URI) | `createResource*()` | `AsyncResourceSpecification` | `createResourceConfig()` |
| Resource Template | `createResourceTemplate*()` | `ResourceTemplate` | `createResourceTemplateUser()` |
| Prompt | `createPrompt*()` | `AsyncPromptSpecification` | `createPromptTranslate()` |

Each method must:
- Declare the definition object (`Tool`, `Resource`, `ResourceTemplate`, or `Prompt`) as a local
  variable before the specification, matching the existing style in the file
- Return a fully configured specification wrapping the definition and its handler lambda
- Log entry using `logger.info(...)` as the **first statement** inside every handler lambda
- Use `Mono.just(...)` for synchronous results; use `exchange.createMessage(...)` + `.map()` only
  for Sampling-backed tools
- Return an MCP error result (`isError = true`) instead of throwing on recoverable failures
- Follow the existing Javadoc style: short one-line `@code` summary sentence, `<p>` paragraph
  with behavioural detail, `@return` tag

---

## Step 2 — Register in all three server subclasses

Call the new factory method and pass its result to the server builder in **all three** files:

- `src/main/java/edu/java/service/StdioServer.java`
- `src/main/java/edu/java/service/SseServer.java`
- `src/main/java/edu/java/service/StreamableSseServer.java`

In each file's `buildServer()` method:
1. Declare a local variable for the new specification in the correct group (step 3 of the builder
   section, alongside the existing declarations of the same type)
2. Pass it to the appropriate builder call — `.tools(...)`, `.resources(...)`,
   `.resourceTemplates(...)`, or `.prompts(...)`

All three files must be updated. A primitive registered in only one or two transports will cause
the integration tests to diverge.

---

## Step 3 — Add a concrete URI resource for testability (Resources only)

If the new primitive is a `Resource` and its URI needs to be readable in the integration tests,
also add a second `AsyncResourceSpecification` with the exact test URI, following the pattern of
`createResourceEchoJunit()` in `Server.java`. Register it in all three server subclasses
alongside the primary resource.

---

## Step 4 — Build and verify

Run the full build with tests:

```
cmd.exe /c "D:\Development\SetupEnvMaven.cmd && D:\Development\SetupEnvJava21.cmd && mvn clean package"
```

All three integration tests must remain green:
```
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

If any test fails, diagnose the failure before proceeding to documentation.

---

## Step 5 — Update `README.md`

Add the new primitive to the appropriate table in the **Registered MCP Primitives** section of
`README.md`:

- **Tools** → `### Tools` table: add `name` and `Description` columns
- **Resources** → `### Resources` table: add `URI`, `MIME type`, and `Description` columns
- **Resource Templates** → `### Resource Templates` table: add `URI pattern` and `Description`
- **Prompts** → `### Prompts` table: add `Name`, `Required arguments`, `Optional arguments`,
  and `Description` columns

If the new primitive is a Sampling-backed tool, also add an entry or note in the
**Sampling** section describing the round-trip flow.
