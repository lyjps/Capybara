# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Compile
mvn compile

# Package into executable jar
mvn package

# Interactive REPL mode (default jar entrypoint)
java -jar target/claude-code-java-demo-1.0-SNAPSHOT.jar [workspace-path]

# Single-task mode (auto-analyze then exit)
mvn exec:java -Dexec.args="/path/to/project"
```

Java 17 required. Zero external dependencies — only JDK standard library.

## Configuration

Three-level priority: **environment variable > root `application.properties` > `src/main/resources/application.properties`**.

Root `application.properties` is gitignored. Copy `application.properties.example` to get started.

Key config keys: `model.provider` (rules|openai|anthropic), `anthropic.auth-token`, `anthropic.model`, `anthropic.base-url`, `max-output-tokens`.

## Architecture

This is a Java reimplementation of Claude Code's core agent loop — not a line-by-line port, but the architectural skeleton: message-driven loop, tool call/result protocol, permission governance, context compaction, and model adapter decoupling.

### Core Loop (agent/)

`AgentEngine` runs: user message → model reply → extract `ToolCallBlock`s → `ToolOrchestrator` executes → tool results append to `ConversationMemory` → next model call. Loop exits when model returns no tool calls.

`ConversationMemory` triggers `SimpleContextCompactor` when messages exceed threshold. **Critical constraint**: compaction must never split tool_use/tool_result pairs — Anthropic API returns 400 if a `tool_result` has no matching `tool_use` in a prior assistant message.

### Message Protocol (message/)

`ContentBlock` is a sealed interface with four variants: `TextBlock`, `ToolCallBlock`, `ToolResultBlock`, `SummaryBlock`. All live in a unified `ConversationMessage` stream — model output and tool feedback share the same chain.

### Model Layer (model/ and model/llm/)

`ModelAdapter` interface decouples the agent loop from any specific model. Two implementations:
- `RuleBasedModelAdapter` — deterministic rule engine for testing the loop without API calls
- `LlmBackedModelAdapter` — real HTTP calls via `LlmProviderClient` implementations

`LlmConversationMapper` translates internal message protocol to provider-specific formats. It handles the tricky mapping: assistant messages with `ToolCallBlock`s become Anthropic `tool_use` content blocks; `ToolResultBlock` messages become `tool_result` content blocks with matching `tool_use_id`.

`AnthropicProviderClient` supports both streaming SSE (`generateStream()`) and non-streaming (`generate()`). Streaming is used in interactive mode for real-time token output. The provider does all JSON construction and parsing manually (no Jackson/Gson) to maintain zero dependencies.

`ModelAdapterFactory` wires everything: provider selection, `ToolRegistry` injection, optional `StreamCallback`.

### Tool System (tool/)

`Tool` interface: `metadata()` + `execute(input, context)`. `ToolMetadata` carries name, description, parameter schemas, and governance flags (readOnly, concurrencySafe, destructive, pathDomain).

`ToolOrchestrator` partitions calls into batches — concurrent-safe tools run in parallel via `ExecutorService`, others run sequentially. `PermissionPolicy` (implemented by `WorkspacePermissionPolicy`) evaluates before execution.

`ToolExecutionContext` separates read scope (`workspaceRoot`) from write scope (`artifactRoot`).

### Two Entry Points

- `InteractiveApplication` — REPL with streaming, `/quit`, `/clear`, `/model` commands
- `DemoApplication` — single goal execution, non-streaming
