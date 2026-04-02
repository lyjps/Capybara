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

`ConversationMemory` implements a **three-level adaptive context compression** system (matching TS `autoCompact.ts`):
- Token-budget trigger: `threshold = contextWindowTokens - maxOutputTokens - autoCompactBuffer` (default 170,616 tokens)
- Level 1 **Micro Compact** (`MicroCompactor`): clears expired tool results, keeps recent N
- Level 2 **Session Memory Compact** (`SessionMemoryCompactor`): zero-API compression using `SessionMemory` content
- Level 3 **Full Compact** (`FullCompactor`): structured summary with User Intent / Tool Trace / Key Decisions / Unfinished Work / Recently Accessed Files
- Circuit breaker: stops after 3 consecutive failures
- All compactors return unified `CompactResult` record

**Critical constraint**: compaction must never split tool_use/tool_result pairs — Anthropic API returns 400 if a `tool_result` has no matching `tool_use` in a prior assistant message. Protected at three levels: `ConversationMemory.adjustTailForToolPairing()`, `SessionMemoryCompactor.adjustCutPointForToolPairing()`, and `LlmConversationMapper.ensureToolPairing()`.

The `compact/` package contains: `TokenEstimator` (char-level heuristics: ASCII ~4c/token, CJK ~1.5c/token), `MicroCompactConfig`, `SessionMemory` (10-section Markdown template matching TS MDK), `CompactResult`, `CompactType`.

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

9 tools registered: `list_files`, `read_file`, `write_file`, `agent`, `send_message`, `task_create`, `task_get`, `task_list`, `task_update`.

### Agent Subprocess System (agent/)

Thread-level isolation of TS's process-level agent isolation. Each sub-agent runs in its own thread with:
- Independent `ConversationMemory` (context window isolation)
- Filtered `ToolRegistry` (per `AgentDefinition.isToolAllowed()`)
- Independent `AgentEngine` instance

Key classes:
- `AgentDefinition` — agent type definition record (agentType, allowedTools, disallowedTools, readOnly, maxTurns)
- `BuiltInAgents` — `GENERAL_PURPOSE` (wildcard tools) and `EXPLORE` (read-only)
- `AgentRegistry` — ConcurrentHashMap registry of agent type definitions
- `SubAgentRunner` — `runSync()` blocks, `runAsync()` returns `AsyncAgentHandle` (agentId + CompletableFuture)
- `AgentTask` — per-agent state tracking with `ConcurrentLinkedQueue<String>` for inter-agent messaging
- `AgentTaskRegistry` — global task registry, supports find by ID or name, message delivery

Inter-agent communication: `SendMessageTool` enqueues to target's `pendingMessages`; `AgentEngine.consumePendingMessages()` drains queue each turn and injects as user messages.

Recursive protection: sub-agents don't receive AgentTool or SendMessageTool in their filtered tool list.

### Task Management System (task/)

In-memory task store for tracking work items across agents:
- `Task` — immutable record with wither methods, status (PENDING/IN_PROGRESS/COMPLETED)
- `TaskStore` — `ConcurrentHashMap` + `AtomicInteger` ID generator, thread-safe CRUD
- 4 task tools: `task_create`, `task_get`, `task_list`, `task_update` (supports "deleted" status for deletion)

### Two Entry Points

- `InteractiveApplication` — REPL with streaming, `/quit`, `/clear`, `/model`, `/agents`, `/tasks`, `/compact`, `/context` commands
- `DemoApplication` — single goal execution, non-streaming

## Tests

223 unit tests covering agent definitions, registries, sub-agent execution, task management, tool interactions, and three-level context compression (TokenEstimator, MicroCompactor, SessionMemory, SessionMemoryCompactor, FullCompactor, ConversationMemory integration). All tests use mock ModelAdapters (no real API needed).

```bash
mvn test                          # Run all tests
mvn test -Dtest=AgentToolTest     # Run single test class
```

**重要重要** 每次代码更新完，都需要在doc目录的文档下面更新实现代码的内容和实现方案的解释说明。
