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
- 支持命令：`/quit`（退出）、`/clear`（清空对话历史）、`/model`（显示模型信息）
- 支持 **流式输出**：通过 `StreamCallback` 实现打字机效果
- 启动方式：`java -jar target/claude-code-java-demo-1.0-SNAPSHOT.jar [工作区路径]`

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

### 3.3 对话记忆与上下文管理

#### `ConversationMemory`

管理完整的对话历史，核心参数：
- `maxMutableMessages = 24`：可变消息上限
- `tailSize = 12`：压缩时保留的尾部消息数

**上下文压缩机制：**

当可变消息超过 24 条时触发 `maybeCompact()`：
1. 将前部旧消息交给 `SimpleContextCompactor` 压缩为一条摘要
2. 保留最近 12 条消息作为尾部
3. **关键安全措施**：`adjustTailForToolPairing()` 确保 `tool_use` / `tool_result` 配对不被拆分

#### `SimpleContextCompactor`

将一批旧消息浓缩为一条 SYSTEM 级别的 `SummaryBlock`：
- 每条消息取 `role + 内容前 140 字符`
- 最多处理 10 条消息

#### 工具配对保护

Anthropic API 严格要求每个 `tool_result` 的 `tool_use_id` 必须在前一条 assistant 消息中有对应的 `tool_use`。系统在两个层面保护这一约束：

1. **Memory 层**：`adjustTailForToolPairing()` 在压缩切分时，向前扫描找到匹配的 assistant 消息
2. **Mapper 层**：`LlmConversationMapper.ensureToolPairing()` 过滤掉孤立的 `tool_result`

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
- [x] **上下文自动压缩**：超过阈值时自动压缩旧消息，保持对话窗口可控
- [x] **工具调用配对安全**：两层保护确保 tool_use/tool_result 不会被拆分

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
│   ├── AgentEngine.java              # 核心 Agent 循环（含子 Agent 消息消费）
│   ├── AgentDefinition.java          # Agent 类型定义 record
│   ├── AgentRegistry.java            # Agent 类型注册中心
│   ├── AgentResult.java              # Agent 执行结果 record
│   ├── AgentTask.java                # Agent 运行状态追踪（含消息队列）
│   ├── AgentTaskRegistry.java        # 全局 Agent 任务注册表
│   ├── AsyncAgentHandle.java         # 异步 Agent 句柄（agentId + Future）
│   ├── BuiltInAgents.java            # 内置 Agent 定义（general-purpose, Explore）
│   ├── SubAgentRunner.java           # 子 Agent 执行引擎（同步/异步）
│   ├── ConversationMemory.java       # 对话记忆与上下文压缩
│   ├── ContextCompactor.java         # 压缩器接口
│   └── SimpleContextCompactor.java   # 简单摘要压缩实现
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
5. 注册 9 个工具：
   - 文件工具：list_files, read_file, write_file
   - 任务工具：task_create, task_get, task_list, task_update
   - Agent 工具：agent, send_message
6. 创建 ModelAdapter → 通过反射注入到 SubAgentRunner（解决循环依赖）
```

新增 REPL 命令：
- `/agents` — 列出所有运行中的子 Agent
- `/tasks` — 列出所有任务

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
| `TaskStoreTest` | 25 | CRUD、状态转换、并发创建/更新、clear 重置 |
| `TaskToolsTest` | 25 | 4 个任务工具的完整执行、参数校验、元数据 |
| `AgentToolTest` | 12 | 同步/异步模式、Agent 类型解析、注册验证、元数据 |
| `SendMessageToolTest` | 10 | 消息投递（ID/名称）、未知目标、参数校验、多消息 |

**总计：108 个测试，全部通过。**

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

11. **108 项单元测试**：使用 ECHO_MODEL（无需真实 API 的 ModelAdapter lambda）+ 纯逻辑测试，覆盖 Agent 定义、注册、执行、任务管理、工具交互、消息通信全链路。
