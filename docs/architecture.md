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
  ├── ListFilesTool    (list_files)   — 列出目录树，深度 ≤6，过滤隐藏/构建目录
  ├── ReadFileTool     (read_file)    — 读取文件内容，截断 6000 字符
  └── WriteFileTool    (write_file)   — 写入文件，仅限 artifactRoot 目录
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
│   ├── AgentEngine.java              # 核心 Agent 循环
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
└── tool/
    ├── Tool.java                     # 工具接口
    ├── ToolMetadata.java             # 工具元数据
    ├── ToolExecutionContext.java      # 执行上下文（workspace + artifact 路径）
    ├── ToolOrchestrator.java         # 工具编排（并发/串行）
    ├── ToolRegistry.java             # 工具注册表
    ├── PermissionPolicy.java         # 权限策略接口
    ├── WorkspacePermissionPolicy.java# 工作区权限实现
    └── impl/
        ├── ListFilesTool.java        # 列出文件
        ├── ReadFileTool.java         # 读取文件
        └── WriteFileTool.java        # 写入文件
```

---

## 七、设计亮点

1. **零外部依赖**：整个项目仅使用 JDK 17 标准库，无 Jackson、无 OkHttp、无 Spring，所有 JSON 和 HTTP 操作手写实现，展示了 Java 标准库的完整能力。

2. **sealed interface 消息模型**：`ContentBlock` 使用 sealed interface，配合 pattern matching，在编译期保证所有消息类型都被正确处理。

3. **策略模式权限控制**：`PermissionPolicy` 接口解耦了权限检查逻辑，便于替换为更复杂的策略（如用户确认、白名单等）。

4. **工具配对双重保护**：在 Memory 层和 Mapper 层双重保证 tool_use/tool_result 不被拆分，从根本上避免 Anthropic API 400 错误。

5. **流式与非流式统一接口**：`StreamCallback` 为可选参数，同一套代码同时支持流式和非流式模式，无需分叉逻辑。

6. **三级配置优先级**：环境变量 → 项目配置 → 内置默认值，兼顾开发便利性和部署灵活性。
