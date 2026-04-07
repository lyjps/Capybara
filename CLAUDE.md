# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Compile
mvn compile

# Package into executable jar
mvn package

# Interactive REPL mode (default jar entrypoint)
java -jar target/Capybara-1.0-SNAPSHOT.jar [workspace-path]

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

`ContentBlock` is a sealed interface with five variants: `TextBlock`, `ToolCallBlock`, `ToolResultBlock`, `SummaryBlock`, `ToolReferenceBlock`. All live in a unified `ConversationMessage` stream — model output and tool feedback share the same chain. `ToolReferenceBlock` is used by the tool deferred loading system to mark discovered tools.

### Model Layer (model/ and model/llm/)

`ModelAdapter` interface decouples the agent loop from any specific model. Two implementations:
- `RuleBasedModelAdapter` — deterministic rule engine for testing the loop without API calls
- `LlmBackedModelAdapter` — real HTTP calls via `LlmProviderClient` implementations

`LlmConversationMapper` translates internal message protocol to provider-specific formats. It handles the tricky mapping: assistant messages with `ToolCallBlock`s become Anthropic `tool_use` content blocks; `ToolResultBlock` messages become `tool_result` content blocks with matching `tool_use_id`. Supports dual-collection `toRequest(conversation, model, filteredTools, allTools)` for deferred loading — `filteredTools` determines which schemas are sent, `allTools` determines which get `deferLoading=true` flag.

`AnthropicProviderClient` supports both streaming SSE (`generateStream()`) and non-streaming (`generate()`). Streaming is used in interactive mode for real-time token output. The provider does all JSON construction and parsing manually (no Jackson/Gson) to maintain zero dependencies.

`ModelAdapterFactory` wires everything: provider selection, `ToolRegistry` injection, optional `StreamCallback`.

### Tool System (tool/)

`Tool` interface: `metadata()` + `execute(input, context)`. `ToolMetadata` carries name, description, parameter schemas, governance flags (readOnly, concurrencySafe, destructive, pathDomain), and deferred loading flags (isMcp, shouldDefer, alwaysLoad, searchHint).

`ToolOrchestrator` partitions calls into batches — concurrent-safe tools run in parallel via `ExecutorService`, others run sequentially. `PermissionPolicy` (implemented by `WorkspacePermissionPolicy`) evaluates before execution. Includes schema-not-sent hint injection when deferred tools are invoked without loaded schemas.

`ToolExecutionContext` separates read scope (`workspaceRoot`) from write scope (`artifactRoot`).

**Tool Deferred Loading** (tool search mechanism, see `doc/tool-deferred-loading.md`):
- `ToolSearchUtils` — core utility: `isDeferredTool()`, `filterToolsForApi()`, `extractDiscoveredToolNames()`, `buildSchemaNotSentHint()`, `checkAutoThreshold()`
- `ToolSearchTool` (`tool/impl/`) — `ToolSearch` tool for discovering deferred tools via keyword search or `select:` direct mode
- `ToolReferenceBlock` — content block marking discovered tools (`[tool_reference] tool_name` format)
- Three modes via `TOOL_SEARCH_MODE` env var: `TST` (always defer), `TST_AUTO` (auto-threshold), `STANDARD` (default, no deferral)
- MCP tools (`isMcp=true`) are deferred by default; `alwaysLoad=true` tools are never deferred

11 tools registered: `list_files`, `read_file`, `write_file`, `agent`, `send_message`, `task_create`, `task_get`, `task_list`, `task_update`, `ToolSearch`, `Skill` (conditional — only when skill files exist).

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

### Streaming Tool Execution (tool/streaming/)

Performance optimization that executes tool_use blocks as they complete during SSE streaming, instead of waiting for the full model response. See `doc/streaming-tool-execution.md` for detailed architecture.

**Core mechanism:** `AnthropicProviderClient.parseSseStream()` detects `content_block_stop` for tool_use → notifies `StreamingToolCallback.onToolUseComplete()` → `StreamingToolExecutor.addTool()` queues and immediately schedules execution via thread pool → tools run in parallel with continued SSE streaming.

**Key classes:**
- `StreamingToolExecutor` — core executor with TrackedTool state machine (QUEUED→EXECUTING→COMPLETED/FAILED), concurrent-safe vs exclusive scheduling (`schedulingLock`), insertion-order result collection via `CompletableFuture`
- `StreamingToolCallback` — extends `StreamCallback` (without breaking `@FunctionalInterface`) adding `onToolUseComplete(ToolCallBlock)` notification
- `StreamingToolConfig` — feature gate via `ENABLE_STREAMING_TOOL_EXECUTION` env var (modes: ENABLED/DISABLED/AUTO, default DISABLED)

**Concurrency model:** concurrent-safe tools (`concurrencySafe=true`) run in parallel; non-concurrent-safe tools get exclusive access (wait for all others to complete). Scheduling triggered from SSE reader thread (`addTool`) and pool worker threads (`executeTool` finally block).

**Integration points:**
- `AgentEngine` — 9-arg constructor accepts `StreamCallback` + `ToolRegistry`; `executeLoop()` branches between streaming path (`callModelWithStreamingTools()`) and classic path based on `StreamingToolConfig.isEnabled()`
- `ModelAdapter` — new default `nextReply(conversation, context, callback)` overload for streaming callback passthrough
- `LlmBackedModelAdapter` — `doNextReply()` private method with callback override support
- `ToolOrchestrator` — new `executeSingleTool()` public method for streaming executor to call

**Backward compatibility:** all changes are additive — old constructors preserved, default method on interface, instanceof guard on callback, feature gate defaults to DISABLED.

### MCP Protocol Integration (mcp/)

MCP (Model Context Protocol) support for dynamic tool/resource discovery from external servers. Zero external dependencies — hand-written JSON-RPC 2.0 and transport layers.

**Package structure:**
- `mcp/protocol/` — `SimpleJsonParser` (zero-dep JSON), `JsonRpcRequest`, `JsonRpcResponse`, `JsonRpcError`
- `mcp/transport/` — `McpTransport` interface, `StdioTransport` (subprocess stdin/stdout), `StreamableHttpTransport` (POST 一发一收), `LegacySseTransport` (经典 SSE 双通道: GET /sse + POST /message?sessionId=xxx)
- `mcp/auth/` — `McpAuthConfig` (OAuth 配置 record), `JwtTokenProvider` (HS256 JWT + OAuth2 Client Credentials + Token Exchange 两步认证，零外部依赖)
- `mcp/client/` — `McpClient` (initialize/listTools/callTool/listResources/readResource), `McpConnectionManager` (multi-server lifecycle, batch connect, exponential backoff reconnect, SSE/HTTP/STDIO 路由)
- `mcp/tool/` — `McpToolBridge` (MCP tool → Java `Tool` 直通适配器), `MappedMcpTool` (带名称映射+参数注入的适配器), `ToolMapping` (映射规则 record), `MappedToolRegistry` (12个美团工具的映射定义工厂), `ListMcpResourcesTool`, `ReadMcpResourceTool`
- `mcp/` — `McpConfigLoader` (`.mcp.json` + `~/.claude/settings.json` discovery, 支持 auth 块解析), `McpNameUtils` (`mcp__<server>__<tool>` naming), `McpPermissionPolicy`, config/state records

**Tool naming convention:** `mcp__<normalizedServer>__<normalizedTool>` (double underscore separator, `[^a-zA-Z0-9_-]` → `_`).

**Config discovery:** project `.mcp.json` > user `~/.claude/settings.json`. Supports `${VAR}` and `${VAR:-default}` environment variable expansion.

**Connection management:** batch connect (stdio batch 3, remote batch 10), states PENDING→CONNECTED/FAILED/DISABLED, exponential backoff reconnect for remote (1s→30s, max 5 attempts).

**三种传输层实现:**
- `StdioTransport` — 子进程 stdin/stdout（用于 amap-proxy.js）
- `LegacySseTransport` — 经典 SSE 双通道（GET /sse 持久流 + POST /message?sessionId=xxx），用于 xt-search 美团搜索 MCP
- `StreamableHttpTransport` — Streamable HTTP（每次独立 POST），支持动态 header 注入，用于 mt-map 美团地图 MCPHub（带 JWT OAuth 认证）

**JWT OAuth 认证（mt-map）:** `JwtTokenProvider` 实现两步 OAuth 流程：
1. Client Credentials Grant（HS256 JWT Assertion → initial access_token）
2. Token Exchange Grant（initial token → MCP server scoped token）
Token 缓存至过期前 5 分钟自动刷新。零外部依赖（使用 JDK `javax.crypto.Mac` 实现 HMAC-SHA256）。

**工具名称映射层:** `MappedMcpTool` + `ToolMapping` + `MappedToolRegistry` 将上游 MCP 工具名映射为对 LLM 友好的名字，同时处理：
- 字面映射：`meituan_search_mix` → upstream `offline_meituan_search_mix`
- 模板映射：`mt_map_direction` → upstream `{mode}`（运行时从参数取值替换）
- 系统参数注入：自动注入 lat/lng/userId 等固定参数
- 参数删除：转发前移除 originalQuery 等参数

9 built-in tools + dynamic MCP tools (`mcp__<server>__<tool>`) + 12 mapped tools (4 美团搜索 + 8 美团地图) + optional MCP resource tools.

**美团搜索工具（通过 xt-search SSE MCP 直连）:** `meituan_search_mix`(综合搜索), `content_search`(内容搜索), `id_detail_pro`(详情查询), `meituan_search_poi`(店铺内搜索)

**美团地图工具（通过 mt-map Streamable HTTP MCP + JWT 直连）:** `mt_map_geo`(地理编码), `mt_map_regeo`(逆地理编码), `mt_map_text_search`(关键词搜索), `mt_map_nearby`(周边搜索), `mt_map_direction`(路径规划), `mt_map_distance`(距离测量), `mt_map_iplocate`(IP定位), `mt_map_poiprovide`(POI详情)

### Skill System (skill/)

User-configured skill loading and execution. Skills are Markdown files (`.md`) with YAML frontmatter metadata, stored in `~/.claude/skills/` (user-level) and `<project>/.claude/skills/` (project-level). Project-level skills override user-level skills with the same name.

**Package structure:**
- `SkillDefinition` — immutable record: name, description, whenToUse, allowedTools, model, context (INLINE/FORK), arguments, promptTemplate, sourceFile, source (USER/PROJECT)
- `SkillLoader` — scans directories for skill files in two layouts: flat mode (`skills/*.md`) and directory mode (`skills/<name>/SKILL.md` for subdirectories/symlinks). Parses YAML frontmatter (zero-dep hand-written parser supporting key-value, quoted strings, inline/indented lists, nested objects, booleans), builds `SkillDefinition` list. Directory mode derives skill name from parent directory name.
- `SkillRegistry` — `LinkedHashMap<String, SkillDefinition>` registry with `findByName()` (exact + `.md` extension fallback), `buildSkillListing()` for system prompt injection
- `SkillTool` — `Tool` implementation: model calls `Skill({skill: "name", args: "..."})` → lookup → resolvePrompt (`$ARGUMENTS` substitution) → inline (return prompt in `<skill>` tags) or fork (`SubAgentRunner.runSync()`)

**Two execution modes:**
- **inline** (default): skill prompt returned as `ToolResult`, model processes it in current conversation
- **fork**: sub-agent created via `SubAgentRunner` with independent `ConversationMemory` and filtered `ToolRegistry`

**Invocation paths:**
- Model calls `Skill` tool with skill name + optional args
- User types `/skill_name args...` in REPL (handled by `InteractiveApplication` command parser)
- `/skills` command lists all loaded skills

**Integration:** `InteractiveApplication` loads skills at startup via `SkillLoader.loadAll()`, registers `SkillTool` (only when skills exist), passes `SkillRegistry` to `SystemPromptBuilder.buildMainPrompt()` for skill listing injection. Backward-compatible: old 5-arg `buildMainPrompt()` delegates to new 6-arg overload with `null` skillRegistry.

### System Prompt (prompt/)

`SystemPromptBuilder` produces the system prompt from modular sections for 美团Agent「小团」(life-scenario agent, NOT an AI coding assistant).

**Static sections** (constant per build, in `SystemPromptSections`, all Chinese):
- `ROLE_AND_GUIDELINES` — 美团Agent「小团」角色定义 + 坚持满足用户需求 + 工具使用原则 + 搜索结果完整性三原则
- `SYSTEM` — 系统行为规范（Markdown、并行调用、注入防护、自动压缩）
- `OUTPUT_STYLE` — 输出风格（简洁有结构、推荐给关键信息、列表/表格）
- `DEFAULT_AGENT_PROMPT` / `AGENT_NOTES` / `TOOL_RESULT_SUMMARY` — 子代理兜底和工具结果提醒

**Dynamic sections** (generated at runtime by `SystemPromptBuilder`):
- `availableToolsSection(enabledToolNames)` — lists 美团搜索/内容搜索/美团地图/Agent/Task/Skill tools with Chinese descriptions
- `skillListingSection(skillRegistry)` — generates available skill listing from SkillRegistry
- `EnvironmentInfo.collect(cwd, modelName)` — CWD, git, platform, Java version, shell, model
- `languageSection(language)` — "请始终使用{language}回复用户"
- `memorySection(workspaceRoot)` — loads CLAUDE.md from workspace root
- `mcpInstructionsSection(mcpManager)` — MCP server-provided instructions

**Three assembly methods:**
- `buildMainPrompt()` — interactive REPL (ROLE_AND_GUIDELINES + SYSTEM + availableTools + skillListing + OUTPUT_STYLE + env + language + memory + mcp + toolResultSummary)
- `buildDemoPrompt()` — single-task mode (ROLE_AND_GUIDELINES + OUTPUT_STYLE + env + memory)
- `buildAgentPrompt()` — sub-agents (agent definition prompt + AGENT_NOTES + env + memory)

### Two Entry Points

- `InteractiveApplication` — REPL with streaming, `/quit`, `/clear`, `/model`, `/agents`, `/tasks`, `/compact`, `/context`, `/mcp` commands
- `DemoApplication` — single goal execution, non-streaming

## Tests

723 unit tests covering agent definitions, registries, sub-agent execution, task management, tool interactions, three-level context compression, MCP protocol integration (SSE/HTTP transports, JWT auth, tool name mapping), system prompt modular construction (美团生活场景角色定义、搜索完整性原则、可用工具动态段、环境信息、CLAUDE.md 加载), tool deferred loading (ToolSearchUtils filtering/discovery/threshold, ToolSearchTool select/keyword search, ToolReferenceBlock protocol, LlmConversationMapper integration), streaming tool execution (StreamingToolExecutor state machine/concurrency/scheduling, StreamingToolCallback routing, StreamingToolConfig feature gate, integration with ToolOrchestrator and AgentEngine), skill system (SkillDefinition record/defaults/validation, SkillLoader file parsing/YAML frontmatter/directory scanning with flat and directory mode support, SkillRegistry lookup/listing, SkillTool inline/fork execution, end-to-end integration with SystemPromptBuilder). All tests use mock ModelAdapters and mock transports (no real API calls or network needed).

```bash
mvn test                          # Run all tests
mvn test -Dtest=AgentToolTest     # Run single test class
```

**重要重要** 每次代码更新完，都需要在doc目录的文档下面更新实现代码的内容和实现方案的解释说明。
