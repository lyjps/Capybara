# Claude Code Java — 架构与功能说明文档

## 一、项目概述

**Claude Code Java** 是一个用纯 Java 17（零外部依赖）重新实现的 Claude Code 核心 Agent Loop 架构。项目展示了如何从零构建一个具备工具调用能力的 AI Agent 系统，支持 Anthropic / OpenAI 兼容的大模型后端，具备流式输出、上下文压缩、权限控制等生产级特性。

### 技术栈

- **Java 17**（sealed interface、record、text block、switch expression）
- **零外部依赖**：所有 HTTP 请求使用 `java.net.http.HttpClient`，所有 JSON 解析/构建均为手写
- **构建工具**：Maven，打包为可执行 JAR

---

## 二、整体架构

```
┌──────────────────────────────────────────────────────┐
│                    Entry Points                       │
│  ┌─────────────────┐    ┌──────────────────────────┐ │
│  │ DemoApplication  │    │ InteractiveApplication   │ │
│  │ (单次任务模式)    │    │ (交互式 REPL 模式)       │ │
│  └────────┬────────┘    └───────────┬──────────────┘ │
│           │                         │                 │
│           └──────────┬──────────────┘                 │
│                      ▼                                │
│              ┌───────────────┐                        │
│              │  AgentEngine  │ ← 核心 Agent 循环       │
│              └───────┬───────┘                        │
│           ┌──────────┼──────────┐                     │
│           ▼          ▼          ▼                     │
│   ┌────────────┐ ┌────────┐ ┌──────────────────┐    │
│   │ModelAdapter │ │Memory  │ │ToolOrchestrator  │    │
│   │(模型适配)   │ │(上下文) │ │(工具编排执行)     │    │
│   └──────┬─────┘ └────────┘ └────────┬─────────┘    │
│          ▼                           ▼               │
│   ┌─────────────────┐      ┌─────────────────┐      │
│   │ProviderClient   │      │  Tool Registry  │      │
│   │(Anthropic/OpenAI)│      │  + Permission   │      │
│   └─────────────────┘      └─────────────────┘      │
└──────────────────────────────────────────────────────┘
```

---

## 三、核心模块详解

### 3.1 入口层（Entry Points）

#### `DemoApplication` — 单次任务模式

- 接收一个目标字符串（goal），执行一轮完整的 Agent 循环后退出
- 适用于脚本化、批处理场景
- 调用 `engine.run(goal, eventSink)`

#### `InteractiveApplication` — 交互式 REPL 模式

- 终端交互界面，持续读取用户输入
- 支持命令：`/quit`（退出）、`/clear`（清空对话历史）、`/model`（显示模型信息）、`/compact`（手动触发压缩）、`/context`（显示 Token 用量统计）
- 支持 **流式输出**：通过 `StreamCallback` 实现打字机效果
- 启动方式：`java -jar target/Capybara-1.0-SNAPSHOT.jar [工作区路径]`

### 3.2 Agent 引擎（`AgentEngine`）

Agent 循环是整个系统的核心，实现了经典的 ReAct（Reason + Act）模式：

```
用户输入 → 模型回复 → 提取工具调用 → 执行工具 → 结果反馈 → 模型再次回复 → ... → 最终文本回复
```

**关键设计：**

| 特性 | 说明 |
|------|------|
| 最大轮次 | 12 轮（防止无限循环） |
| 双模式入口 | `run()` 用于单次任务，`chat()` 用于多轮对话（共享 Memory） |
| 事件回调 | `eventSink` 接收调试信息（灰色 ANSI 输出），与最终回复区分 |
| 安全兜底 | 达到最大轮次时返回友好提示，而非抛异常 |

**执行循环 `executeLoop()` 流程：**

1. 调用 `modelAdapter.nextReply(conversation, context)` 获取模型回复
2. 将回复追加到 Memory
3. 提取回复中的 `ToolCallBlock` 列表
4. 若无工具调用 → 返回最终回复文本
5. 若有工具调用 → 通过 `ToolOrchestrator` 执行 → 将结果作为 `tool_result` 追加到 Memory → 回到步骤 1

### 3.3 对话记忆与三级上下文压缩系统

#### 概述

对应 Claude Code TypeScript 原版 `autoCompact.ts` 的 `hWK()` 压缩决策链。从旧版基于消息计数（24 条）触发的简单截断，重构为 **Token 预算驱动的三级自适应压缩**：

```
Token 用量 → 超过阈值？ → Level 1: Micro Compact（清理过期工具结果）
                              ↓ 仍然超阈值
                          Level 2: Session Memory Compact（零 API 调用，用内存替代）
                              ↓ 仍然超阈值或不可用
                          Level 3: Full Compact（结构化摘要生成）
```

**核心阈值公式（与 TS 对齐）：**
```
autoCompactThreshold = contextWindowTokens - maxOutputTokens - autoCompactBuffer
默认: 200,000 - min(16,384, 20,000) - 13,000 = 170,616 tokens
```

#### `ConversationMemory` — 核心管理器

新构造函数参数：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `contextWindowTokens` | 200,000 | 上下文窗口总大小 |
| `maxOutputTokens` | 16,384 (cap 20K) | 最大输出 Token，从窗口中扣除 |
| `autoCompactBuffer` | 13,000 | 预留缓冲区（对应 TS U87） |
| `sessionMemory` | null | 会话内存实例 |
| `microCompactConfig` | DEFAULT | 微压缩配置 |

**压缩决策链 `maybeCompact()`：**
1. 估算当前 Token 用量（`TokenEstimator`）
2. 若 tokens < threshold → 不压缩
3. 若 `consecutiveCompactFailures >= 3` → 熔断保护，不压缩
4. 执行三级压缩级联：Micro → Session Memory → Full
5. 成功后重置熔断计数器，更新 `lastCompactResult`

**向后兼容**：旧构造函数 `ConversationMemory(SimpleContextCompactor, maxMessages, tailSize)` 仍可使用，内部切换到 `useLegacyMode` 路径。

#### Level 1: Micro Compact（`MicroCompactor`）

对应 TS 原版 `microCompact.ts` 的 `Hd_()` 函数。

**策略**：不压缩整个对话，只替换单个过期工具结果的内容。

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `enabled` | false | 是否启用 |
| `gapThresholdMinutes` | 60 | 距上次 assistant 消息的最小间隔 |
| `keepRecent` | 5 | 保留最近 N 个工具结果不清理 |

可清理的工具类型：`list_files`, `read_file`, `write_file`

**执行流程**：
1. 检查启用状态 + 时间间隔
2. 收集所有可清理的 `ToolResultBlock`
3. 保留最近 `keepRecent` 个
4. 其余替换为 `"[Old tool result content cleared — saved ~N tokens]"`
5. 消息结构不变，仅替换内容

#### Level 2: Session Memory Compact（`SessionMemoryCompactor`）

对应 TS 原版 `sessionMemoryCompact.ts` 的 `i3Y()` 算法。**零 API 调用**的压缩路径。

**阈值常量（与 TS mp8 对齐）：**

| 常量 | 值 | 说明 |
|------|-----|------|
| `MIN_TOKENS` | 10,000 | 最小累积 Token 才触发 |
| `MIN_TEXT_BLOCK_MESSAGES` | 5 | 最小文本消息数 |
| `MAX_TOKENS` | 40,000 | 超过此值强制触发 |

**算法**：从末尾向前扫描，累积 Token 计数，满足条件时确定切割点。用 `SessionMemory` 的当前内容作为摘要替换被切割的早期消息。

#### Level 3: Full Compact（`FullCompactor`）

对应 TS 原版 `compact.ts` 的 `LE6()` 函数，替代旧版 `SimpleContextCompactor`。

**结构化摘要生成**（不同于旧版的简单截断）：

| 摘要章节 | 提取方法 |
|----------|----------|
| User Intent | 扫描 user 消息，提取请求要点（最多 5 条） |
| Tool Call Trace | 按时间顺序记录 `tool_name(params) → result_summary`（最多 20 条） |
| Key Decisions | 提取 assistant 消息中的推理文本（最多 5 条） |
| Unfinished Work | 检查最后 assistant 是否有未完成的工具调用 |
| Recently Accessed Files | 从 `read_file`/`write_file` 提取路径（最多 5 个） |

**输出格式（与 TS P18() 对齐）**：
```
This session is being continued from a previous conversation that ran out of context.
The summary below covers the earlier portion of the conversation.

<summary>
## User Intent
...
## Tool Call Trace
...
## Key Decisions
...
## Unfinished Work
...
## Recently Accessed Files
...
</summary>

Continue the conversation from where it left off...
```

#### `SessionMemory` — 会话内存

对应 TS 原版 `sessionMemory.ts`。10 节 Markdown 模板（与 TS MDK 变量完全对齐）：

```
# Session Title        — 第一条 user 消息前 60 字符
# Current State        — 最近 3 条 assistant 消息摘要
# Task Specification   — 第一条 user 消息完整内容
# Files and Functions  — 所有 read_file/write_file 的路径
# Workflow             — 工具调用序列
# Errors & Corrections — 所有 error=true 的 ToolResultBlock
# Codebase and System Documentation
# Learnings
# Key Results          — 所有 write_file 调用的输出
# Worklog              — 按轮次记录 Turn N: [tool_calls] summary
```

限制：每节最大 2,000 tokens，总计最大 12,000 tokens。

Java 版使用**规则提取**（不调用 LLM API），从对话消息中自动填充各节。

#### `TokenEstimator` — Token 估算

对应 TS 原版 `Cj4()` 函数。无实际 tokenizer，使用字符级启发式：

| 字符类型 | 估算比率 |
|----------|----------|
| ASCII | ~4 字符 = 1 token |
| CJK（中日韩） | ~1.5 字符 = 1 token |
| 其他 Unicode | ~2 字符 = 1 token |
| 图片 | 固定 2,000 tokens |

#### 工具配对保护

Anthropic API 严格要求每个 `tool_result` 的 `tool_use_id` 必须在前一条 assistant 消息中有对应的 `tool_use`。系统在三个层面保护这一约束：

1. **Memory 层**：`adjustTailForToolPairing()` 在压缩切分时，向前扫描找到匹配的 assistant 消息（递归处理，最多 10 次迭代）
2. **SessionMemoryCompactor 层**：`adjustCutPointForToolPairing()` 单独实现切割点修正
3. **Mapper 层**：`LlmConversationMapper.ensureToolPairing()` 过滤掉孤立的 `tool_result`

#### `SimpleContextCompactor`（已弃用）

旧版实现，标记为 `@Deprecated`，仅用于向后兼容。将一批旧消息浓缩为一条 SYSTEM 级别的 `SummaryBlock`：每条消息取 `role + 内容前 140 字符`，最多处理 10 条消息。

### 3.4 模型适配层

#### 三层架构

```
ModelAdapter（接口）
  ├── RuleBasedModelAdapter     ← 确定性规则，用于测试
  └── LlmBackedModelAdapter     ← 真实大模型
          └── LlmProviderClient（接口）
                ├── AnthropicProviderClient  ← Anthropic Messages API
                └── OpenAiProviderClient     ← OpenAI Chat Completions API
```

#### `ModelRuntimeConfig` — 三级配置优先级

配置加载优先级（高到低）：

1. **环境变量**（如 `ANTHROPIC_AUTH_TOKEN`、`ANTHROPIC_MODEL`）
2. **项目根目录 `application.properties`**（已 gitignore）
3. **classpath `application.properties`**（内置默认值）

支持的配置项：

| 配置键 | 环境变量 | 说明 |
|--------|----------|------|
| `model.provider` | `MODEL_PROVIDER` | 模型提供方：`anthropic` / `openai` / `rules` |
| `anthropic.api-key` | `ANTHROPIC_API_KEY` / `ANTHROPIC_AUTH_TOKEN` | API 密钥 |
| `anthropic.base-url` | `ANTHROPIC_BASE_URL` | API 端点（支持企业网关） |
| `anthropic.model` | `ANTHROPIC_MODEL` | 模型名称 |
| `max-output-tokens` | `MAX_OUTPUT_TOKENS` | 最大输出 token 数 |

#### `ModelAdapterFactory` — 工厂模式

根据 `provider` 配置自动创建对应的 `ModelAdapter`：
- `ANTHROPIC` → `LlmBackedModelAdapter` + `AnthropicProviderClient`
- `OPENAI` → `LlmBackedModelAdapter` + `OpenAiProviderClient`
- `RULES` → `RuleBasedModelAdapter`

支持可选的 `StreamCallback` 参数，传 `null` 则使用非流式模式。

#### `LlmConversationMapper` — 消息格式转换

将内部 `ConversationMessage` 体系转换为 LLM API 所需的 `LlmMessage` 格式：
- 提取 system prompt + SummaryBlock → 系统提示词
- `ToolCallBlock` → `LlmMessage.Type.TOOL_CALLS`
- `ToolResultBlock` → `LlmMessage.Type.TOOL_RESULT`（携带 `toolUseId`）
- 内置 `ensureToolPairing()` 防止孤立 tool_result

### 3.5 Anthropic API 客户端（`AnthropicProviderClient`）

项目中最复杂的模块（约 600 行），完整实现了 Anthropic Messages API 的调用：

#### 非流式模式

```
buildRequestBody(request, stream=false) → doPost(endpoint, body) → parseResponse(responseBody)
```

#### 流式模式（SSE）

```
buildRequestBody(request, stream=true) → doPost(BodyHandlers.ofInputStream()) → parseSseStream()
```

**SSE 事件处理：**

| 事件 | 处理逻辑 |
|------|----------|
| `content_block_start` (text) | 标记当前为文本块 |
| `content_block_start` (tool_use) | 记录工具调用 id 和 name |
| `content_block_delta` (text_delta) | 调用 `callback.onTextToken(text)` 实时输出 + 累积到 buffer |
| `content_block_delta` (input_json_delta) | 累积 tool input JSON 片段 |
| `content_block_stop` | 若为 tool_use 块，解析完整 JSON 为 Map，创建 ToolCallData |
| `message_stop` | 组装最终 `LlmResponse`，结束流 |

**请求体构建：**
- 手动拼接 JSON，包含完整的工具 schema（name、description、input_schema 含 properties/required）
- 消息体支持三种格式：纯文本、工具调用（tool_use content blocks）、工具结果（tool_result）
- 支持企业网关 URL 格式适配

**所有 JSON 操作均为手写**：`extractJsonStringValue()`、`findClosingBrace()`、`skipPastString()` 等工具方法。

### 3.6 工具系统

#### 工具注册与发现

```
ToolRegistry
  ├── ListFilesTool    (list_files)      — 列出目录树，深度 ≤6，过滤隐藏/构建目录
  ├── ReadFileTool     (read_file)       — 读取文件内容，截断 6000 字符
  ├── WriteFileTool    (write_file)      — 写入文件，仅限 artifactRoot 目录
  ├── AgentTool        (agent)           — 启动子 Agent（同步/异步）
  ├── SendMessageTool  (send_message)    — Agent 间消息通信
  ├── TaskCreateTool   (task_create)     — 创建任务
  ├── TaskGetTool      (task_get)        — 查询任务详情
  ├── TaskListTool     (task_list)       — 列出所有任务
  └── TaskUpdateTool   (task_update)     — 更新/删除任务
```

每个工具通过 `ToolMetadata` 声明：
- `name`、`description`：用于生成 JSON Schema 传给模型
- `readOnly`、`concurrencySafe`、`destructive`：用于编排和权限控制
- `pathDomain`、`pathInputKey`：用于路径安全检查
- `List<ParamInfo>`：参数列表，含 name、description、required

#### `ToolOrchestrator` — 工具编排执行

- 将工具调用分为 **并发安全批次** 和 **串行执行批次**
- 使用 `ExecutorService`（4 线程）并行执行标记为 `concurrencySafe` 的工具
- 实现 `AutoCloseable`，确保线程池正确关闭

#### `WorkspacePermissionPolicy` — 权限策略

- **路径边界检查**：所有文件操作必须在 workspaceRoot 内
- **写操作限制**：destructive 操作仅允许在 artifactRoot（`output/` 目录）内
- 防止 AI 越权读写系统文件

### 3.7 消息模型

#### 内部消息体系

```
ConversationMessage (record)
  ├── role: Role (SYSTEM / USER / ASSISTANT)
  └── blocks: List<ContentBlock>
                  ├── TextBlock        — 纯文本
                  ├── ToolCallBlock    — 工具调用请求 (id, toolName, input)
                  ├── ToolResultBlock  — 工具执行结果 (toolUseId, toolName, error, content)
                  └── SummaryBlock     — 上下文压缩摘要
```

`ContentBlock` 使用 Java 17 的 **sealed interface**，编译期保证类型安全。

#### LLM 通信消息

```
LlmRequest  → systemPrompt + List<LlmMessage> + modelName + List<ToolSchema>
LlmMessage  → TEXT / TOOL_CALLS / TOOL_RESULT
LlmResponse → text + List<ToolCallData>
```

---

## 四、已实现功能清单

### 核心能力

- [x] **完整 Agent Loop**：消息 → 模型推理 → 工具调用 → 结果反馈 → 再推理的完整闭环
- [x] **多轮工具调用**：单次对话中支持最多 12 轮工具调用
- [x] **流式输出（SSE Streaming）**：实时逐 token 输出到终端，打字机效果
- [x] **三级上下文自适应压缩**：Token 预算驱动的 Micro → Session Memory → Full 三级级联压缩
- [x] **Session Memory 会话内存**：10 节 Markdown 模板，规则提取（零 API 调用）
- [x] **Token 估算器**：中英文混合字符级启发式估算
- [x] **工具调用配对安全**：三层保护确保 tool_use/tool_result 不会被拆分
- [x] **压缩熔断机制**：连续失败 3 次自动停止尝试

### 模型支持

- [x] **Anthropic Claude**：完整的 Messages API 集成，支持企业网关
- [x] **OpenAI 兼容**：Provider 框架已搭建（skeleton）
- [x] **规则引擎**：无需 API 的确定性规则模式，用于开发测试

### 工具系统

- [x] **文件列表** (`list_files`)：递归列出目录结构
- [x] **文件读取** (`read_file`)：读取文件内容
- [x] **文件写入** (`write_file`)：在安全沙箱内写入文件
- [x] **子 Agent 工具** (`agent`)：启动子 Agent 执行多步骤任务，支持同步/异步模式
- [x] **Agent 通信** (`send_message`)：通过名称或 ID 向其他 Agent 发送消息
- [x] **任务管理** (`task_create/get/list/update`)：完整的任务 CRUD，支持状态流转和依赖关系
- [x] **并发执行**：安全的工具可并行执行
- [x] **权限控制**：路径边界 + 写操作沙箱

### 配置管理

- [x] **三级配置优先级**：环境变量 > 项目根配置 > 内置默认值
- [x] **企业网关支持**：自定义 base URL + 认证 token
- [x] **零配置启动**：默认使用 rules 模式，无需 API key

### 交互模式

- [x] **单次任务模式**（`DemoApplication`）：执行单个目标后退出
- [x] **交互式 REPL**（`InteractiveApplication`）：持续对话，支持命令
- [x] **对话历史管理**：`/clear` 清空，自动压缩

---

## 五、数据流示例

以用户在交互模式输入"列出当前目录的文件"为例：

```
1. 用户输入 "列出当前目录的文件"
   ↓
2. InteractiveApplication 调用 engine.chat(input, logEvent)
   ↓
3. AgentEngine.executeLoop():
   ├── 将 user message 追加到 Memory
   ├── 调用 modelAdapter.nextReply(conversation, context)
   │   ├── LlmConversationMapper 将 ConversationMessage → LlmMessage
   │   ├── AnthropicProviderClient.generateStream() 发送 SSE 请求
   │   ├── 收到 text_delta → callback.onTextToken() → 终端实时打印
   │   ├── 收到 tool_use(list_files) → 累积工具调用信息
   │   └── 返回 LlmResponse(text + toolCalls)
   ├── 检测到 toolCalls 非空
   ├── ToolOrchestrator 执行 list_files 工具
   │   ├── WorkspacePermissionPolicy 检查路径权限
   │   └── ListFilesTool.execute() 返回目录树
   ├── 将 tool_result 追加到 Memory
   ├── 再次调用 modelAdapter.nextReply()
   │   └── 模型基于工具结果生成最终回复（流式输出）
   └── 无更多工具调用 → 返回最终文本
   ↓
4. 终端显示完整回复
```

---

## 六、项目结构

```
src/main/java/com/co/claudecode/demo/
├── DemoApplication.java              # 单次任务入口
├── InteractiveApplication.java       # 交互式 REPL 入口
├── agent/
│   ├── AgentEngine.java              # 核心 Agent 循环（含子 Agent 消息消费 + 压缩事件）
│   ├── AgentDefinition.java          # Agent 类型定义 record
│   ├── AgentRegistry.java            # Agent 类型注册中心
│   ├── AgentResult.java              # Agent 执行结果 record
│   ├── AgentTask.java                # Agent 运行状态追踪（含消息队列）
│   ├── AgentTaskRegistry.java        # 全局 Agent 任务注册表
│   ├── AsyncAgentHandle.java         # 异步 Agent 句柄（agentId + Future）
│   ├── BuiltInAgents.java            # 内置 Agent 定义（general-purpose, Explore）
│   ├── SubAgentRunner.java           # 子 Agent 执行引擎（同步/异步）
│   ├── ConversationMemory.java       # 对话记忆 + Token 预算 + 三级压缩决策
│   ├── ContextCompactor.java         # 压缩器接口（旧版）
│   └── SimpleContextCompactor.java   # 简单摘要压缩（@Deprecated，向后兼容）
├── compact/
│   ├── CompactType.java              # 压缩类型枚举（NONE/MICRO/SESSION_MEMORY/FULL）
│   ├── CompactResult.java            # 压缩结果 record（统一三级输出）
│   ├── MicroCompactConfig.java       # 微压缩配置 record
│   ├── TokenEstimator.java           # Token 估算器（字符级启发式）
│   ├── MicroCompactor.java           # Level 1: 微型压缩（清理过期工具结果）
│   ├── SessionMemory.java            # 会话内存核心（10 节 Markdown 模板）
│   ├── SessionMemoryCompactor.java   # Level 2: 会话内存压缩（零 API 调用）
│   └── FullCompactor.java            # Level 3: 完整压缩（结构化摘要生成）
├── message/
│   ├── ContentBlock.java             # sealed interface (Text/ToolCall/ToolResult/Summary)
│   ├── ConversationMessage.java      # 对话消息 record
│   ├── Role.java                     # SYSTEM / USER / ASSISTANT
│   ├── TextBlock.java
│   ├── ToolCallBlock.java
│   ├── ToolResultBlock.java
│   └── SummaryBlock.java
├── model/
│   ├── ModelAdapter.java             # 模型适配接口
│   ├── RuleBasedModelAdapter.java    # 确定性规则模型
│   └── llm/
│       ├── ModelAdapterFactory.java      # 模型工厂
│       ├── ModelRuntimeConfig.java       # 运行时配置（三级优先级）
│       ├── ModelProvider.java            # 枚举：ANTHROPIC / OPENAI / RULES
│       ├── LlmBackedModelAdapter.java    # 真实模型适配器
│       ├── LlmProviderClient.java        # Provider 接口
│       ├── AbstractLlmProviderClient.java# 公共基类
│       ├── AnthropicProviderClient.java  # Anthropic 完整实现（含 SSE）
│       ├── OpenAiProviderClient.java     # OpenAI 骨架
│       ├── LlmConversationMapper.java    # 消息格式转换
│       ├── LlmRequest.java              # 请求模型
│       ├── LlmResponse.java             # 响应模型
│       ├── LlmMessage.java              # LLM 消息格式
│       └── StreamCallback.java           # 流式回调接口
├── mcp/
│   ├── McpTransportType.java         # 传输类型枚举（STDIO/SSE/HTTP）
│   ├── McpConnectionState.java       # 连接状态枚举（PENDING/CONNECTED/FAILED/DISABLED）
│   ├── McpServerConfig.java          # 服务器配置 record
│   ├── McpServerConnection.java      # 连接状态持有者
│   ├── McpToolInfo.java              # 工具信息 record
│   ├── McpResourceInfo.java          # 资源信息 record
│   ├── McpNameUtils.java             # 命名工具（mcp__<server>__<tool> 约定）
│   ├── McpConfigLoader.java          # 配置发现 + 环境变量展开
│   ├── McpPermissionPolicy.java      # MCP 感知的权限策略
│   ├── protocol/
│   │   ├── JsonRpcRequest.java       # JSON-RPC 2.0 请求
│   │   ├── JsonRpcResponse.java      # JSON-RPC 2.0 响应
│   │   ├── JsonRpcError.java         # JSON-RPC 错误码
│   │   └── SimpleJsonParser.java     # 零依赖 JSON 工具
│   ├── transport/
│   │   ├── McpTransport.java              # 传输层接口
│   │   ├── StdioTransport.java            # 子进程 stdin/stdout 传输
│   │   ├── StreamableHttpTransport.java   # Streamable HTTP 传输（POST 一发一收，支持动态 header）
│   │   └── LegacySseTransport.java        # 经典 SSE 双通道传输（GET /sse + POST /message）
│   ├── auth/
│   │   ├── McpAuthConfig.java             # OAuth2 认证配置 record
│   │   └── JwtTokenProvider.java          # HS256 JWT + 2步 OAuth 认证 + token 缓存
│   ├── client/
│   │   ├── McpClient.java                 # MCP 客户端
│   │   ├── McpInitResult.java             # 初始化结果
│   │   ├── McpResourceContent.java        # 资源内容
│   │   └── McpConnectionManager.java      # 多服务器连接管理器（SSE/HTTP/STDIO 路由）
│   └── tool/
│       ├── McpToolBridge.java             # MCP 工具 → Tool 直通适配器
│       ├── MappedMcpTool.java             # 带名称映射+参数注入的 MCP 工具适配器
│       ├── ToolMapping.java               # 工具名称映射规则 record（字面/模板）
│       ├── MappedToolRegistry.java        # 映射工具工厂（12个美团/地图工具定义）
│       ├── ListMcpResourcesTool.java      # 内建: 列出 MCP 资源
│       └── ReadMcpResourceTool.java       # 内建: 读取 MCP 资源
├── task/
│   ├── Task.java                     # 任务数据模型 record（不可变 + wither）
│   ├── TaskStatus.java               # 任务状态枚举（PENDING/IN_PROGRESS/COMPLETED）
│   └── TaskStore.java                # 内存任务存储（ConcurrentHashMap）
└── tool/
    ├── Tool.java                     # 工具接口
    ├── ToolMetadata.java             # 工具元数据
    ├── ToolResult.java               # 工具执行结果 record
    ├── ToolExecutionContext.java      # 执行上下文（workspace + artifact 路径）
    ├── ToolOrchestrator.java         # 工具编排（并发/串行）
    ├── ToolRegistry.java             # 工具注册表
    ├── PermissionPolicy.java         # 权限策略接口
    ├── PermissionDecision.java       # 权限决策 record
    ├── WorkspacePermissionPolicy.java# 工作区权限实现
    └── impl/
        ├── ListFilesTool.java        # 列出文件
        ├── ReadFileTool.java         # 读取文件
        ├── WriteFileTool.java        # 写入文件
        ├── AgentTool.java            # 启动子 Agent 工具
        ├── SendMessageTool.java      # Agent 间通信工具
        ├── TaskCreateTool.java       # 创建任务工具
        ├── TaskGetTool.java          # 查询任务工具
        ├── TaskListTool.java         # 列出任务工具
        └── TaskUpdateTool.java       # 更新任务工具

src/test/java/com/co/claudecode/demo/
├── agent/
│   ├── AgentDefinitionTest.java      # 7 tests — Agent 定义与工具过滤
│   ├── AgentRegistryTest.java        # 5 tests — Agent 注册与查找
│   ├── AgentTaskRegistryTest.java    # 8 tests — 任务注册、消息投递、清理
│   └── SubAgentRunnerTest.java       # 16 tests — 同步/异步执行、并发
├── compact/
│   ├── TokenEstimatorTest.java       # 19 tests — 中英文混合估算、消息/列表估算
│   ├── MicroCompactorTest.java       # 12 tests — 时间触发、工具过滤、Token 节省
│   ├── SessionMemoryTest.java        # 20 tests — 模板结构、内容提取、各节填充
│   ├── SessionMemoryCompactorTest.java # 13 tests — Token 阈值、切割点、工具配对
│   ├── FullCompactorTest.java        # 22 tests — 结构化摘要、用户意图、文件恢复
│   ├── ConversationMemoryTest.java   # 21 tests — 三级压缩、熔断、向后兼容
│   └── CompactIntegrationTest.java   # 8 tests — 端到端压缩流水线验证
├── mcp/
│   ├── protocol/
│   │   ├── SimpleJsonParserTest.java     # 33 tests — JSON 构建/解析/转义/嵌套/数组
│   │   └── JsonRpcMessageTest.java       # 8 tests — 请求序列化/响应解析/错误码
│   ├── transport/
│   │   ├── StdioTransportTest.java            # 10 tests — 启动/发送/接收/关闭/错误
│   │   ├── StreamableHttpTransportTest.java   # 10 tests — 状态/URL/动态header/错误
│   │   └── LegacySseTransportTest.java        # 14 tests — 状态/URL解析/endpoint解析/origin检查/open竞态保护
│   ├── auth/
│   │   ├── McpAuthConfigTest.java             # 4 tests — 验证/空字段/访问器
│   │   └── JwtTokenProviderTest.java          # 11 tests — JWT构造/HS256/Base64URL/缓存
│   ├── client/
│   │   ├── McpClientTest.java                 # 14 tests — initialize/tools/resources/截断
│   │   └── McpConnectionManagerTest.java      # 12 tests — 批量连接/禁用/失败/查询
│   ├── tool/
│   │   ├── McpToolBridgeTest.java             # 11 tests — 元数据/参数/execute/工厂
│   │   ├── ToolMappingTest.java               # 14 tests — 字面/模板映射/参数注入/删除
│   │   ├── MappedMcpToolTest.java             # 8 tests — 元数据/映射/execute错误/空输入
│   │   ├── MappedToolRegistryTest.java        # 18 tests — 搜索/地图/全量/模板/服务器名
│   │   ├── ListMcpResourcesToolTest.java      # 5 tests — 元数据/空结果/过滤
│   │   └── ReadMcpResourceToolTest.java       # 6 tests — 元数据/验证/错误处理
│   ├── McpNameUtilsTest.java                  # 15 tests — 规范化/构建/解析/前缀
│   ├── McpServerConfigTest.java               # 10 tests — 工厂/传输类型/禁用/auth
│   ├── McpServerConnectionTest.java           # 10 tests — 状态转换/格式化
│   ├── McpConfigLoaderTest.java               # 17 tests — 文件解析/环境变量/合并
│   ├── McpPermissionPolicyTest.java           # 8 tests — 委托/允许/拒绝/优先级
│   └── McpIntegrationTest.java                # 8 tests — 端到端集成验证
├── task/
│   ├── TaskStoreTest.java            # 25 tests — CRUD、并发、状态转换
│   └── TaskToolsTest.java            # 25 tests — 4 个任务工具完整测试
└── tool/impl/
    ├── AgentToolTest.java            # 12 tests — 同步/异步模式、类型解析
    └── SendMessageToolTest.java      # 10 tests — 消息投递、参数校验
```

---

## 七、Agent 子进程系统

### 7.1 设计动机与演进路径

Claude Code 的 TypeScript 原版使用操作系统进程隔离来实现子 Agent（每个 Agent 是独立的 Node.js 进程）。Java 版将这一架构简化为**线程级隔离**：每个子 Agent 在独立线程中运行独立的 `AgentEngine` 实例，拥有独立的 `ConversationMemory`。

```
单线程 Agent Loop  →  多工具并发执行  →  Agent 子进程（线程隔离）
                                              │
                    ┌─────────────────────────┼────────────────────────────┐
                    ▼                         ▼                            ▼
              同步子 Agent              异步子 Agent                  Agent 间通信
           (阻塞等待完成)          (后台执行，立即返回 ID)          (SendMessage 消息队列)
```

### 7.2 核心架构

```
┌──────────────────────────────────────────────────────────────────────┐
│                      Agent 子进程系统                                 │
│                                                                      │
│  ┌──────────────┐     ┌──────────────┐     ┌─────────────────┐      │
│  │AgentDefinition│     │ AgentRegistry │     │BuiltInAgents    │      │
│  │(Agent 类型定义)│◄────│(类型注册中心) │◄────│(内置 Agent 定义) │      │
│  └──────┬───────┘     └──────────────┘     └─────────────────┘      │
│         │                                                            │
│         ▼                                                            │
│  ┌──────────────────┐                                               │
│  │  SubAgentRunner   │ ← 子 Agent 执行引擎                           │
│  │ ┌──────────────┐ │     ┌───────────────────┐                     │
│  │ │ runSync()    │ │────►│独立 ConversationMemory                  │
│  │ │ runAsync()   │ │     │独立 ToolRegistry（过滤后）               │
│  │ │              │ │     │独立 AgentEngine 实例                     │
│  │ └──────────────┘ │     └───────────────────┘                     │
│  └────────┬─────────┘                                               │
│           │                                                          │
│           ▼                                                          │
│  ┌──────────────────┐     ┌──────────────────┐                      │
│  │ AgentTaskRegistry │◄───│    AgentTask       │                      │
│  │(全局任务注册表)    │     │(运行状态 + 消息队列)│                      │
│  └──────────────────┘     └──────────────────┘                      │
│           │                                                          │
│           ▼                                                          │
│  ┌──────────────────┐     ┌──────────────────┐                      │
│  │    AgentTool      │     │  SendMessageTool  │                      │
│  │(启动子 Agent)     │     │(Agent 间通信)      │                      │
│  └──────────────────┘     └──────────────────┘                      │
└──────────────────────────────────────────────────────────────────────┘
```

### 7.3 Agent 定义与注册

#### `AgentDefinition` — Agent 类型定义 (record)

| 字段 | 类型 | 说明 |
|------|------|------|
| `agentType` | String | 类型标识，如 "general-purpose"、"Explore" |
| `whenToUse` | String | 什么场景使用此 Agent |
| `source` | Source | BUILT_IN 或 CUSTOM |
| `systemPrompt` | String | 系统提示词 |
| `allowedTools` | List<String> | 允许的工具列表，null = 允许所有 |
| `disallowedTools` | List<String> | 禁止的工具列表 |
| `readOnly` | boolean | 是否为只读 Agent |
| `maxTurns` | int | 最大执行轮次（默认 12） |
| `model` | String | 可选的模型覆盖 |

**工具过滤逻辑** (`isToolAllowed()`):
1. 先检查 `disallowedTools` → 命中则拒绝
2. 若 `allowedTools == null`（通配符）→ 允许
3. 否则检查 `allowedTools` 是否包含

#### `BuiltInAgents` — 内置 Agent 类型

| Agent 类型 | allowedTools | readOnly | 定位 |
|-----------|-------------|----------|------|
| `general-purpose` | null（全部） | false | 通用多步骤任务执行 |
| `Explore` | [list_files, read_file] | true | 只读代码搜索和探索 |

#### `AgentRegistry` — 类型注册中心

- 基于 `ConcurrentHashMap<String, AgentDefinition>` 存储
- `withBuiltIns()` 工厂方法注册所有内置 Agent
- `resolve(type)` 查找并返回，找不到抛异常
- `findOrNull(type)` 安全查找，找不到返回 null

### 7.4 子 Agent 执行引擎 (`SubAgentRunner`)

核心类，对应 TS 原版 `runAgent()` + `AgentTool.call()` 的逻辑。

**两种执行模式：**

| 模式 | 方法 | 返回值 | 说明 |
|------|------|--------|------|
| 同步 | `runSync()` | `AgentResult` | 阻塞等待子 Agent 完成 |
| 异步 | `runAsync()` | `AsyncAgentHandle` | 立即返回 agentId + CompletableFuture |

**每个子 Agent 拥有完全独立的环境：**

1. **独立 ConversationMemory** — 上下文窗口隔离，不与父 Agent 共享
2. **过滤后的 ToolRegistry** — 根据 `AgentDefinition.isToolAllowed()` 过滤
3. **独立 ToolOrchestrator** — 独立的线程池
4. **独立 AgentEngine** — 独立的执行循环

**递归保护**：子 Agent 的工具集中不包含 `AgentTool` 和 `SendMessageTool`，防止子 Agent 无限嵌套。

**异步执行的 `AsyncAgentHandle` 模式**：
```java
// agentId 在提交到线程池之前生成，立即可用
String agentId = AgentTaskRegistry.generateAgentId();
AgentTask agentTask = new AgentTask(agentId, ...);
taskRegistry.register(agentTask);  // 立即注册

CompletableFuture<AgentResult> future = CompletableFuture.supplyAsync(() -> {
    // 独立线程中执行 Agent 循环
    ...
}, executor);

return new AsyncAgentHandle(agentId, agentType, future);
```

### 7.5 Agent 任务追踪

#### `AgentTask` — 运行状态追踪

每个运行中或已完成的 Agent 实例对应一个 AgentTask：

| 字段 | 类型 | 说明 |
|------|------|------|
| `agentId` | String | 8 字符 UUID 前缀 |
| `name` | String | 可选名称，用于 SendMessage 按名寻址 |
| `status` | Status | RUNNING / COMPLETED / FAILED / KILLED |
| `pendingMessages` | ConcurrentLinkedQueue<String> | 接收其他 Agent 的消息 |
| `aborted` | AtomicBoolean | 外部取消信号 |
| `result` | AgentResult | 完成后填充 |

#### `AgentTaskRegistry` — 全局注册表

- `ConcurrentHashMap<String, AgentTask>` 按 ID 索引
- `ConcurrentHashMap<String, String>` 按名称索引（name → agentId）
- `findByIdOrName()` 支持 ID 或名称查找
- `sendMessage(target, message)` 投递消息到目标 Agent
- `allRunning()` 返回所有运行中的 Agent
- `cleanup()` 清除已完成的任务
- `generateAgentId()` 生成 8 字符唯一 ID

### 7.6 Agent 间通信

#### 发送端：`SendMessageTool`

```
参数：to (目标 Agent ID 或名称), message (文本)
    ↓
AgentTaskRegistry.sendMessage(to, message)
    ↓
AgentTask.enqueueMessage(message) → pendingMessages.add(message)
```

#### 接收端：`AgentEngine.consumePendingMessages()`

每轮 `executeLoop()` 开始时检查消息队列：
```
agentTask.hasPendingMessages()?
    ↓ yes
agentTask.drainMessages() → List<String>
    ↓
每条消息 → memory.append(ConversationMessage.user("[Message from another agent] " + msg))
    ↓
模型下一轮推理时能看到这些消息
```

### 7.7 任务管理系统

#### 数据模型

`Task` (record) — 不可变，通过 wither 方法更新：
- `id` (String, 原子递增)、`subject`、`description`
- `status` (TaskStatus: PENDING / IN_PROGRESS / COMPLETED)
- `owner` (String, nullable)
- `blocks` / `blockedBy` (List<String>)
- `metadata` (Map<String, String>)

`TaskStore` — 内存存储：
- `ConcurrentHashMap<String, Task>` + `AtomicInteger` ID 生成
- `updateTask()` 使用 `compute()` 保证原子更新
- 线程安全，支持多 Agent 并发访问

#### 任务工具

| 工具名 | 参数 | 功能 |
|--------|------|------|
| `task_create` | subject, description | 创建任务，返回 ID |
| `task_get` | taskId | 返回任务详情 |
| `task_list` | 无 | 返回所有任务摘要 |
| `task_update` | taskId, status?, subject?, description?, owner?, addBlocks?, addBlockedBy? | 更新任务，status="deleted" 删除 |

### 7.8 AgentEngine 子 Agent 改造

`AgentEngine` 新增两个构造参数（向后兼容）：

| 参数 | 说明 |
|------|------|
| `agentId` | 当前 Agent 标识（主 Agent 为 null） |
| `agentTask` | AgentTask 引用（用于接收消息和检查取消信号） |

`executeLoop()` 每轮新增两项检查：
1. **取消信号**：`agentTask.isAborted()` → 返回"Agent 已被取消"
2. **消息消费**：`consumePendingMessages()` → 将队列中的消息注入为 user message

### 7.9 与 TS 原版的对比

| 维度 | TypeScript 原版 | Java 实现 |
|------|----------------|-----------|
| 隔离级别 | OS 进程（child_process） | JVM 线程 + 独立 Memory |
| Agent 定义加载 | 文件系统（.md 解析 + JSON Schema） | 编译时定义（record + 工厂方法） |
| 工具过滤 | `filterToolsForAgent()` 运行时过滤 | `AgentDefinition.isToolAllowed()` 过滤 |
| 异步模式 | `async function*` + AbortController | CompletableFuture + AtomicBoolean |
| 任务存储 | 文件系统 JSON + Zod schema | ConcurrentHashMap + record |
| 通信协议 | IPC 进程通信 + handleMessage | ConcurrentLinkedQueue 内存队列 |
| MCP 集成 | initializeAgentMcpServers() | 未实现（超出单进程范围） |
| Worktree 隔离 | git worktree 目录隔离 | 未实现 |
| Swarm 模式 | tmux 多终端协作 | 未实现 |
| Fork 机制 | 父 Agent 对话上下文复制到子 Agent | 未实现 |

### 7.10 入口层集成

`InteractiveApplication` 启动时完成全部初始化：

```
1. 创建 AgentRegistry → 注册内置 Agent 类型
2. 创建 AgentTaskRegistry → 全局 Agent 任务注册表
3. 创建 TaskStore → 全局任务存储
4. 创建 SubAgentRunner → 子 Agent 执行引擎
5. 加载 MCP 配置 (.mcp.json + ~/.claude/settings.json)
6. McpConnectionManager.connectAll() → 批量连接 MCP 服务器（amap=stdio, xt-search=SSE, mt-map=HTTP+JWT）
7. McpToolBridge.createForServers(mgr, {"amap-maps"}) → amap 工具直通适配
8. MappedToolRegistry.createAllTools(mgr) → 创建 12 个映射工具（美团搜索+美团地图）
9. 如有资源 → 添加 ListMcpResourcesTool + ReadMcpResourceTool
10. 注册 9+ 工具：
   - 文件工具：list_files, read_file, write_file
   - 任务工具：task_create, task_get, task_list, task_update
   - Agent 工具：agent, send_message
   - MCP 直通工具：mcp__amap-maps__<tool>（动态数量）
   - MCP 映射工具：meituan_search_mix, content_search, mt_map_geo 等（12个）
   - MCP 内建：mcp_list_resources, mcp_read_resource（如有资源）
11. 创建 ModelAdapter → 通过反射注入到 SubAgentRunner（解决循环依赖）
```

新增 REPL 命令：
- `/agents` — 列出所有运行中的子 Agent
- `/tasks` — 列出所有任务
- `/mcp` — 显示 MCP 服务器连接状态（含 xt-search/mt-map/amap-maps）

---

## 七-B、System Prompt 架构（prompt/）

### 设计理念

面向美团生活场景 Agent「小团」的**模块化、分段式** prompt 构建。采用固定段（角色定义 + 系统规范 + 输出风格）+ 动态段（环境信息 + 语言 + CLAUDE.md + MCP 指令 + 可用工具 + 工具结果提醒）的组合方式。

### Prompt 内容定位

本项目是一个**外卖/生活场景 Agent**，而非 AI 编程助手。System prompt 的核心内容围绕：
- 美团Agent「小团」的角色定义和行为准则
- 美团搜索 / 内容搜索 / 美团地图等工具的串联使用原则
- 搜索结果完整性三原则（混合结果判读、空结果重试、穷举验证）
- 推荐商家/商品时的输出格式规范

### 核心类

| 类 | 职责 |
|---|------|
| `SystemPromptSections` | 6 个静态文本常量（ROLE_AND_GUIDELINES、SYSTEM、OUTPUT_STYLE、DEFAULT_AGENT_PROMPT、AGENT_NOTES、TOOL_RESULT_SUMMARY），全部为中文 |
| `EnvironmentInfo` | 收集运行时环境（CWD、Git 状态、Platform、Java 版本、Shell、模型名），`isGitRepo()` 向上遍历查找 `.git` |
| `SystemPromptBuilder` | 动态段生成 + 3 个组装方法 |

### 静态段说明

| 常量 | 内容 |
|------|------|
| `ROLE_AND_GUIDELINES` | 美团Agent「小团」角色定义 + 重要提醒（坚持满足用户需求、工具使用原则、搜索结果完整性三原则） |
| `SYSTEM` | 系统行为规范（Markdown 格式、并行工具调用、注入防护、自动压缩历史） |
| `OUTPUT_STYLE` | 输出风格（简洁有结构、推荐给关键信息、列表/表格对比、不确定要标注） |
| `DEFAULT_AGENT_PROMPT` | 子代理兜底 prompt |
| `AGENT_NOTES` | 子代理执行指引（绝对路径、不用 emoji） |
| `TOOL_RESULT_SUMMARY` | 工具结果提醒（记录商家名称、地址、评分、价格） |

### 三个组装方法

| 方法 | 用途 | 包含段 |
|------|------|--------|
| `buildMainPrompt()` | 交互式 REPL | ROLE_AND_GUIDELINES + SYSTEM + availableTools + OUTPUT_STYLE + env + language + memory + mcp + toolResultSummary |
| `buildDemoPrompt()` | 单任务模式 | ROLE_AND_GUIDELINES + OUTPUT_STYLE + env + memory |
| `buildAgentPrompt()` | 子 Agent | AgentDefinition.systemPrompt (或 DEFAULT_AGENT_PROMPT) + AGENT_NOTES + env + memory |

### 动态段说明

- **availableToolsSection**: 根据 `enabledToolNames` 集合按需列出美团搜索类（meituan_search_mix、content_search、id_detail_pro、meituan_search_poi）、美团地图类（mt_map_geo、mt_map_regeo 等 8 个）、MCP 直通工具（mcp__ 前缀）、Agent/Task 工具的中文描述
- **memorySection**: 读取工作区根目录 `CLAUDE.md` 文件内容注入 prompt（如不存在则跳过）
- **mcpInstructionsSection**: 从已连接 MCP 服务器收集 `instructions` 字段，格式化为 `## serverName\n{instructions}` 块
- **languageSection**: 基于配置注入 `"请始终使用{language}回复用户"` 指令

### 调用链路

```
InteractiveApplication.main()
  └── SystemPromptBuilder.buildMainPrompt(workspace, model, toolNames, mcpMgr, "Chinese")
        ├── ROLE_AND_GUIDELINES                     ← 固定：角色定义 + 重要提醒
        ├── SYSTEM                                  ← 固定：系统规范
        ├── availableToolsSection(toolNames)        ← 动态：可用工具概览
        ├── OUTPUT_STYLE                            ← 固定：输出风格
        ├── EnvironmentInfo.collect(cwd, model)     ← 动态：环境信息
        ├── languageSection("Chinese")              ← 动态：语言
        ├── memorySection(workspace)                ← 动态：CLAUDE.md
        ├── mcpInstructionsSection(mcpManager)      ← 动态：MCP 指令
        └── TOOL_RESULT_SUMMARY                     ← 固定：工具结果提醒
```

---

## 八、单元测试

### 测试框架

JUnit 5 (jupiter 5.11.4)，零 mock 框架依赖。所有测试使用 `ECHO_MODEL`（立即返回文本回复的 ModelAdapter lambda）或直接测试纯逻辑，无需真实 API。

### 测试覆盖

| 测试类 | 测试数 | 覆盖范围 |
|--------|--------|----------|
| `AgentDefinitionTest` | 7 | 内置 Agent 默认值、工具过滤（通配/显式）、自定义 Agent、验证 |
| `AgentRegistryTest` | 5 | 注册、查找、冲突处理、findOrNull |
| `AgentTaskRegistryTest` | 8 | 注册/查找、消息投递、运行状态过滤、清理、ID 唯一性 |
| `SubAgentRunnerTest` | 16 | 同步/异步执行、失败处理、事件回调、工具过滤、并发 Agent |
| `TokenEstimatorTest` | 19 | ASCII/CJK/混合文本估算、消息级估算、列表累加、空值处理 |
| `MicroCompactorTest` | 12 | 时间触发、工具类型过滤、保留最近 N 个、Token 节省统计 |
| `SessionMemoryTest` | 20 | 模板结构验证、10 节内容提取、Token 截断、空检测 |
| `SessionMemoryCompactorTest` | 13 | Token 阈值判定、切割点计算、工具配对修正、空内存回退 |
| `FullCompactorTest` | 22 | 结构化摘要生成、用户意图/工具轨迹/关键决策提取、文件恢复限制 |
| `ConversationMemoryTest` | 21 | 三级压缩决策链、Token 触发、熔断机制、向后兼容、工具配对 |
| `CompactIntegrationTest` | 8 | 端到端：构造长对话 → 三级压缩 → 验证摘要质量 + 工具配对安全 |
| `TaskStoreTest` | 25 | CRUD、状态转换、并发创建/更新、clear 重置 |
| `TaskToolsTest` | 25 | 4 个任务工具的完整执行、参数校验、元数据 |
| `AgentToolTest` | 12 | 同步/异步模式、Agent 类型解析、注册验证、元数据 |
| `SendMessageToolTest` | 10 | 消息投递（ID/名称）、未知目标、参数校验、多消息 |
| `SimpleJsonParserTest` | 33 | JSON 构建/解析/转义/嵌套/数组/Unicode |
| `JsonRpcMessageTest` | 8 | JSON-RPC 请求序列化/响应解析/错误码常量 |
| `McpNameUtilsTest` | 15 | 规范化/构建/解析/前缀/isMcpTool/displayName |
| `McpServerConfigTest` | 10 | stdio/sse/http/httpWithAuth 工厂方法/disabled/isLocal/isRemote/auth |
| `McpServerConnectionTest` | 10 | 状态转换/重连计数/工具列表/资源/格式化 |
| `McpConfigLoaderTest` | 17 | .mcp.json 解析/settings 解析/环境变量展开/配置合并/边界 |
| `StdioTransportTest` | 10 | 进程启动/JSON-RPC 读写/关闭序列/错误处理 |
| `StreamableHttpTransportTest` | 10 | HTTP 状态管理/URL 验证/动态header/未打开错误 |
| `LegacySseTransportTest` | 14 | SSE 状态管理/URL 解析/endpoint 解析/origin 安全检查/open 竞态保护 |
| `McpAuthConfigTest` | 4 | 配置验证/空字段/record 访问器 |
| `JwtTokenProviderTest` | 11 | JWT 三段构造/HS256 签名/Base64URL/jti唯一/缓存失败 |
| `McpClientTest` | 14 | initialize 握手/listTools/callTool/resources/输出截断/错误 |
| `McpConnectionManagerTest` | 12 | 批量连接/disabled/failed/查询/callTool 路由/常量 |
| `McpToolBridgeTest` | 11 | 元数据映射/参数转换/execute 委托/错误处理/工厂 |
| `ToolMappingTest` | 14 | 字面映射/模板映射/参数注入/参数删除/空值处理 |
| `MappedMcpToolTest` | 8 | 元数据/映射规则/execute 错误处理/空输入/模板解析 |
| `MappedToolRegistryTest` | 18 | 搜索工具数量/地图工具数量/全量/唯一名/模板检查/服务器名 |
| `ListMcpResourcesToolTest` | 5 | 元数据/无资源/过滤/空过滤 |
| `ReadMcpResourceToolTest` | 6 | 元数据/参数验证/服务器未找到/错误处理 |
| `McpPermissionPolicyTest` | 8 | 非MCP委托/允许列表/拒绝列表/优先级/allowAll |
| `McpIntegrationTest` | 8 | 端到端: 配置→命名→JSON-RPC→工具桥接→权限→状态机 |
| `SystemPromptBuilderTest` | 55 | 美团生活场景角色定义/搜索完整性三原则/工具使用原则/可用工具段（搜索/地图/MCP/Agent/Task）/语言段/CLAUDE.md 加载/MCP指令/3种组装方法/Agent prompt/段落顺序 |
| `EnvironmentInfoTest` | 12 | CWD/Java版本/平台/模型名/Git检测/Shell/格式 |

**总计：539 个测试，全部通过。**

运行测试：
```bash
mvn test
```

---

## 九、设计亮点

1. **零外部依赖**：整个项目仅使用 JDK 17 标准库，无 Jackson、无 OkHttp、无 Spring，所有 JSON 和 HTTP 操作手写实现，展示了 Java 标准库的完整能力。

2. **sealed interface 消息模型**：`ContentBlock` 使用 sealed interface，配合 pattern matching，在编译期保证所有消息类型都被正确处理。

3. **策略模式权限控制**：`PermissionPolicy` 接口解耦了权限检查逻辑，便于替换为更复杂的策略（如用户确认、白名单等）。

4. **工具配对双重保护**：在 Memory 层和 Mapper 层双重保证 tool_use/tool_result 不被拆分，从根本上避免 Anthropic API 400 错误。

5. **流式与非流式统一接口**：`StreamCallback` 为可选参数，同一套代码同时支持流式和非流式模式，无需分叉逻辑。

6. **三级配置优先级**：环境变量 → 项目配置 → 内置默认值，兼顾开发便利性和部署灵活性。

7. **线程级 Agent 隔离**：将 TS 的进程级隔离简化为线程级隔离，每个子 Agent 拥有独立的 Memory + ToolRegistry + Engine，既保持了隔离语义又避免了 IPC 开销。

8. **AsyncAgentHandle 模式**：异步 Agent 的 agentId 在提交到线程池之前生成，解决了"需要立即返回 ID 但执行尚未完成"的问题。

9. **ConcurrentLinkedQueue 消息通信**：Agent 间通信使用无锁队列，消费端在 executeLoop() 中定期 drain，无需额外的同步机制。

10. **递归保护**：子 Agent 的过滤工具集自动排除 AgentTool 和 SendMessageTool，防止无限嵌套。

11. **三级自适应压缩**：Micro Compact（清理过期工具结果）→ Session Memory Compact（零 API 调用替换）→ Full Compact（结构化摘要），Token 预算驱动的压缩决策链，与 TS 原版三级策略完全对齐。

12. **MCP 协议集成**：完整实现 MCP（Model Context Protocol）核心子集——零依赖手写 JSON-RPC 2.0 + 双传输层（stdio/SSE）+ 多服务器连接管理（批量连接、指数退避重连）+ 工具桥接（`McpToolBridge` 将 MCP 工具适配为 Java `Tool` 接口）+ 配置发现（`.mcp.json` + `~/.claude/settings.json`）+ MCP 感知权限策略，动态扩展 agent loop 的工具能力。

13. **MCP 直连 + 工具名称映射**：Java 直连三个上游 MCP 服务器（xt-search SSE、mt-map Streamable HTTP + JWT OAuth、amap stdio），无需 Node.js 中间层。`MappedMcpTool` + `ToolMapping` 实现工具名映射（字面/模板）、系统参数注入、参数删除，将 12 个上游工具以 LLM 友好的名称暴露。`JwtTokenProvider` 零依赖实现 HS256 JWT + OAuth2 两步认证。

14. **544 项单元测试**：使用 ECHO_MODEL（无需真实 API 的 ModelAdapter lambda）+ MockTransport（无需真实 MCP 服务器）+ 纯逻辑测试，覆盖 Agent 定义、注册、执行、任务管理、工具交互、消息通信、三级压缩全链路、MCP 协议全链路（SSE 双通道传输、Streamable HTTP 传输、JWT OAuth 认证、工具名称映射）、System Prompt 模块化构建（美团生活场景角色定义、搜索完整性原则、可用工具动态段、环境信息、CLAUDE.md 加载）。
