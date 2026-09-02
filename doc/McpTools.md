# MCP Tools — Architectural Summary

## Context

This document captures the design decisions from an architectural discussion about building a Java MCP server that wraps an OpenAI-compatible LLM provider. It is intended as a self-contained briefing for an AI agent implementing the server.

---

## What to Build

A Java MCP server exposing **two tools** and using the `com.openai:openai-java` SDK to call any OpenAI-compatible endpoint (OpenAI cloud, Ollama, llama.cpp, LM Studio).

### Tool 1: `list_models`

**Purpose:** Discover what models are available at the configured endpoint.

**Parameters:** none

**Implementation:**
- Call `client.models().list().data()` — returns `List<Model>`, all on one page (`hasNextPage()` is always `false`)
- Each `Model` has `.id()` (String) and `.ownedBy()` (String) — no capability fields
- Infer capabilities from model ID string patterns (case-insensitive):

| Pattern in ID | Capability tag |
|---|---|
| `whisper` | `stt` |
| `tts` | `tts` |
| `dall-e`, `gpt-image` | `image-gen` |
| `embed`, `text-embedding` | `embeddings` |
| `moderation` | `moderation` |
| `-vision`, `vision` (only within chat models) | `chat` + `vision` |
| anything else | `chat` |

- Return sorted list of `{ id: string, capabilities: string[] }`
- On any exception: return empty list, log warning — never propagate the error

**Reference implementation:** `edu.java.util.ModelsDiscovery` in the companion `MCP2AI` project already implements this logic exactly.

---

### Tool 2: `review_result`

**Purpose:** Submit Bob's answer to a second, independent LLM for validation. The reviewer uses a different model from Bob's primary LLM — the architectural value comes from that independence.

**Parameters:**
- `user_prompt` (string) — the original question the user asked Bob
- `bob_answer` (string) — Bob's proposed answer

**Implementation:**
- Single `chat.completions.create()` call — **not** multi-turn
- Temperature: `0.0` supplied via `.temperature(0.0)` on `ChatCompletionCreateParams.builder()` — deterministic, consistent verdicts. Use `0.01` instead of exactly `0.0` for local Ollama models that handle the zero boundary poorly.
- System message: the reviewer's instructions (baked into server, overridable via `REVIEWER_SYSTEM_PROMPT` env var)
- User message: `"Question: <user_prompt>\n\nAnswer to review:\n<bob_answer>"`

**Reviewer system prompt (default):**
```
You are a strict technical reviewer. You will be given an original user question
and an AI-generated answer. Evaluate correctness, completeness, and clarity.
Return a JSON object with exactly these fields:
{
  "verdict": "PASS" | "FAIL" | "PARTIAL",
  "confidence": <float 0.0–1.0>,
  "feedback": "<specific explanation of issues or confirmation of correctness>"
}
"verdict" must be exactly one of: PASS, FAIL, PARTIAL
"confidence" must be a float between 0.0 and 1.0
Return only the JSON object. No preamble, no explanation outside the JSON.
```

**How verdict and confidence are produced:** The LLM determines the verdict itself during inference by reasoning over the question and answer and following the system prompt's output format instructions. The `confidence` value is the model's own self-assessed certainty — not a statistical guarantee, but a useful signal (a model reporting `0.3` is telling you it is uncertain). Neither value comes from the SDK; both come from the LLM's text output and must be parsed by the server.

**How to extract verdict and confidence from the SDK response:**

```java
String raw = response.choices().get(0).message().content().orElse("{}");

// Local models sometimes wrap JSON in markdown fences — strip them first
raw = raw.replaceAll("(?s)```json\\s*(.*?)\\s*```", "$1").trim();
// Also handle plain ``` fences
raw = raw.replaceAll("(?s)```\\s*(.*?)\\s*```", "$1").trim();

// Jackson is already a transitive dependency of com.openai:openai-java — no extra dep needed
ObjectMapper mapper = new ObjectMapper();
try {
    JsonNode node = mapper.readTree(raw);
    String verdict    = node.get("verdict").asText();        // "PASS" | "FAIL" | "PARTIAL"
    double confidence = node.get("confidence").asDouble();   // 0.0–1.0
    String feedback   = node.get("feedback").asText();
} catch (JsonProcessingException e) {
    // Model ignored the format instruction — return a safe fallback
    // treat raw text as feedback, confidence low, verdict PARTIAL
}
```

**Enforcing JSON output format:** Where the endpoint supports it, add `.responseFormat(ResponseFormatJsonObject.builder().build())` to `ChatCompletionCreateParams`. This forces the model to produce only valid JSON, eliminating markdown fence wrapping. Most OpenAI cloud models support this; most local Ollama models do **not** — always implement the fallback parsing regardless.

**Return value:** parsed `verdict`, `confidence`, `feedback`, plus `model_used` and `endpoint` added by the server for traceability.

**Why a different model matters:** A reviewer on the same model as Bob shares the same training biases and blind spots. A smaller local model (e.g. `llama3.2:3b`) reviewing output from a large cloud model (e.g. `gpt-4o`) provides genuine independence — it has no social pressure to agree and no knowledge of what the "expected" answer is.

---

## What Was Explicitly Rejected

### Tool: `optimize_input` (prompt/document compression)

**Rejected as a Bobcoin-saving mechanism** for the following reason:

When Bob calls an MCP tool, the full tool input and output are recorded in the Messages transcript. If Bob calls `optimize_input(2000-token prompt)` and receives back `1000 tokens`, the transcript now contains **3000+ tokens** of prompt content — worse than doing nothing.

There is **no interception point inside Bob** where a tool result can replace a user message before it enters the context. The `@file` operator, user messages, and tool results all enter the transcript immediately and in full.

**The only legitimate prompt optimization workflow** is out-of-band and manual:
1. Open a separate chat (zero transcript overhead)
2. Paste raw text, request compression
3. Copy optimized result
4. Open a new chat, paste only the optimized text — the original never appears in that context

This requires no MCP server. It is pure workflow discipline.

**`optimize_input` may still be worth building as a quality tool** (non-native speaker assistance, prompt clarity improvement) but must be understood as a quality improvement tool, not a cost reduction tool.

---

## Configuration

Follow the same pattern as `edu.java.api.Config` in the companion project:

Resolution order: **env var → config file → hard-coded default**

| Key | Purpose | Default |
|---|---|---|
| `REVIEWER_BASE_URL` | Endpoint URL | `http://localhost:11434/v1` |
| `REVIEWER_API_KEY` | API key (any non-empty string for local servers) | `local` |
| `REVIEWER_MODEL` | Model used for all LLM calls | `llama3.2:3b` |
| `REVIEWER_SYSTEM_PROMPT` | Override the default reviewer system prompt | *(built-in default)* |
| `REVIEWER_TEMPERATURE` | Temperature for `review_result` | `0.0` |

All values must be trimmed (`.trim()`) — Windows `set KEY=value` commands append trailing spaces that cause `400: invalid model ID` errors.

---

## SDK Usage

**Dependency:** `com.openai:openai-java:4.52.0`

**Client construction:**
```java
OpenAIOkHttpClient.builder()
    .baseUrl(Config.getBaseUrl())
    .apiKey(Config.getApiKey())
    .timeout(Timeout.builder().request(Duration.ofMinutes(2)).build())
    .build();
```

The 2-minute timeout is important — the SDK default is 10 minutes, and `RetryingHttpClient` retries twice, so a stuck local model can block for 30 minutes without an explicit cap.

**`list_models` call:**
```java
List<Model> models = client.models().list().data();
// Model fields: .id() (String), .ownedBy() (String)
// No capability fields — infer from ID string patterns (see above)
```

**`review_result` call:**
```java
ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
    .addSystemMessage(REVIEWER_SYSTEM_PROMPT)
    .addUserMessage("Question: " + userPrompt + "\n\nAnswer to review:\n" + bobAnswer)
    .model(Config.getModel())
    .temperature(0.0)   // or 0.01 for local models that mishandle exact zero
    .build();

ChatCompletion response = client.chat().completions().create(params);
// Raw content — parse JSON manually (see "review_result" section above for full parsing logic)
String raw = response.choices().get(0).message().content().orElse("{}");
```

**Note:** The SDK has no `getConfidence()` or `getVerdict()` method. The verdict, confidence, and feedback all come from parsing the LLM's text output as JSON. The SDK's `ChatCompletion` object only gives you the raw text string.

**Known SDK gotchas** (from source inspection of `openai-java-core-4.52.0-sources.jar`):

| Issue | Correct API |
|---|---|
| `ChatCompletionUserMessageParam` has no `addContentPart()` | Use `.contentOfArrayOfContentParts(List<ChatCompletionContentPart>)` |
| `SpeechCreateParams.Voice.NOVA` does not exist | `Voice` is a sealed union — use `.voice("nova")` |
| `TranscriptionCreateParams.model()` | Takes `AudioModel`, not `TranscriptionModel` |
| `client.audio().transcriptions().create()` | Returns sealed `TranscriptionCreateResponse` — call `.asTranscription().text()` |
| `ImageModel.DALL_E_2` | From `com.openai.models.images.ImageModel`, not `ImageGenerateParams.Model` |
| `ImagesResponse.data()` | Returns `Optional<List<Image>>` |
| `Moderation.categories()` | Returns `Moderation.Categories` directly, not `Optional` |
| `Moderation.Categories.hate()` etc. | Returns primitive `boolean` |

---

## Token / Cost Architecture (critical understanding)

Bobcoins track both input and output tokens. The full context — system prompt, tool definitions, MCP tool descriptions, rules, skills, and the entire message transcript — is **re-sent to the LLM on every message**. Baseline overhead on a typical project is ~8,500 tokens before any user message.

Connecting this MCP server adds to the **Tool definitions** and **MCP Tools** categories permanently for the lifetime of the session. This is a fixed per-message cost regardless of whether the tools are ever called.

**Implication:** Only connect the MCP server in sessions where you actually intend to use `review_result`. Keeping it always-connected adds overhead to every message in every session.

---

## Reference Implementation

The companion project `MCP2AI` (same repository) contains working implementations of:
- `edu.java.api.Config` — env var → properties → default config loading with trim
- `edu.java.api.ClientFactory` — `OpenAIOkHttpClient` construction
- `edu.java.util.ModelsDiscovery` — `list_models` logic including capability inference
- `edu.java.examples.ChatExample` — single chat completion call pattern

These can be directly copied or adapted for the MCP server implementation.

---
