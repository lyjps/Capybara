# Claude Code Java 版本 — 完整学习指南

> 本指南面向希望深入理解 Claude Code 核心架构的 Java 开发者。从项目概览到每个子系统的实现细节，提供一条从入门到精通的学习路径。

---

## 目录

1. [项目概览](#1-项目概览)
2. [环境搭建与快速运行](#2-环境搭建与快速运行)
3. [推荐阅读顺序](#3-推荐阅读顺序)
4. [项目结构全景](#4-项目结构全景)
5. [核心数据流：一次完整对话的生命周期](#5-核心数据流一次完整对话的生命周期)
6. [子系统详解](#6-子系统详解)
   - 6.1 [消息协议层 (message/)](#61-消息协议层-message)
   - 6.2 [Agent 引擎 (agent/)](#62-agent-引擎-agent)
   - 6.3 [模型适配层 (model/)](#63-模型适配层-model)
   - 6.4 [工具系统 (tool/)](#64-工具系统-tool)
   - 6.5 [上下文压缩系统 (compact/)](#65-上下文压缩系统-compact)
   - 6.6 [MCP 协议集成 (mcp/)](#66-mcp-协议集成-mcp)
   - 6.7 [流式工具执行 (tool/streaming/)](#67-流式工具执行-toolstreaming)
   - 6.8 [工具延迟加载](#68-工具延迟加载)
   - 6.9 [Skill 系统 (skill/)](#69-skill-系统-skill)
   - 6.10 [系统提示词构建 (prompt/)](#610-系统提示词构建-prompt)
   - 6.11 [任务管理系统 (task/)](#611-任务管理系统-task)
7. [关键设计模式与 Java 惯用法](#7-关键设计模式与-java-惯用法)
8. [测试体系与学习方法](#8-测试体系与学习方法)
9. [与 TypeScript 原版的对应关系](#9-与-typescript-原版的对应关系)
10. [进阶学习建议](#10-进阶学习建议)

---

## 1. 项目概览

### 1.1 这是什么项目？

这是 Claude Code（Anthropic 官方 CLI 工具）核心 Agent 循环的 **Java 17 重新实现**。不是逐行翻译，而是提取了架构骨架：

- **消息驱动的 Agent 循环** — 用户输入 → 模型回复 → 工具调用 → 工具结果 → 下一轮模型调用
- **Tool call/result 协议** — 统一的消息流设计
- **权限治理** — 工具执行前的权限检查
- **上下文压缩** — 三级自适应压缩防止上下文溢出
- **模型适配器解耦** — 支持多种 LLM 后端

### 1.2 核心设计哲学

| 原则 | 体现 |
|------|------|
| **零外部依赖** | 只使用 JDK 17 标准库，无 Jackson/Gson/Spring/Netty |
| **手写一切** | JSON 解析、HTTP 客户端、SSE 流解析、JWT 签名全部手写 |
| **密封类型安全** | `sealed interface` + `record` 保证消息协议编译期安全 |
| **线程级隔离** | 子 Agent 通过独立线程 + 独立内存实现隔离（对应 TS 的进程级隔离）|

### 1.3 项目规模

```
源代码:  97 个 Java 文件，分布在 18 个包中
测试代码: 50 个测试文件，723 个单元测试
文档:     9 个文档文件（CLAUDE.md, README.md, doc/×4, docs/×2, pom.xml）
```

---

## 2. 环境搭建与快速运行

### 2.1 前置要求

- **Java 17+**（项目使用 `sealed interface`、`record`、文本块等 Java 17 特性）
- **Maven 3.6+**

### 2.2 配置

三级配置优先级：**环境变量 > 根目录 `application.properties` > `src/main/resources/application.properties`**

```bash
# 复制示例配置文件
cp application.properties.example application.properties

# 关键配置项
model.provider=anthropic          # 可选: rules|openai|anthropic
anthropic.auth-token=sk-ant-xxx   # Anthropic API Key
anthropic.model=claude-sonnet-4-20250514
anthropic.base-url=https://api.anthropic.com
max-output-tokens=16384
```

### 2.3 编译与运行

```bash
# 编译
mvn compile

# 运行全部测试（推荐先跑一遍确认环境正常）
mvn test

# 打包
mvn package

# 交互式 REPL 模式
java -jar target/Capybara-1.0-SNAPSHOT.jar [workspace-path]

# 单任务模式（自动分析后退出）
mvn exec:java -Dexec.args="/path/to/project"
```

### 2.4 两个入口点

| 入口 | 文件 | 用途 |
|------|------|------|
| `InteractiveApplication` | 根包 | REPL 模式，支持流式输出、`/quit`、`/clear`、`/model`、`/agents`、`/tasks`、`/compact`、`/context`、`/mcp`、`/skills` 等命令 |
| `DemoApplication` | 根包 | 单目标执行模式，非流式 |

---

## 3. 推荐阅读顺序

学习这个项目，建议按以下顺序，从核心向外围扩展：

### 第一阶段：理解消息协议（30 分钟）

```
message/ContentBlock.java          ← sealed interface，整个系统的数据基石
message/TextBlock.java             ← 最简单的实现
message/ToolCallBlock.java         ← 模型发出的工具调用
message/ToolResultBlock.java       ← 工具执行结果
message/SummaryBlock.java          ← 压缩后的摘要
message/ToolReferenceBlock.java    ← 延迟加载发现的工具引用
message/ConversationMessage.java   ← record + 工厂方法
message/MessageRole.java           ← USER/ASSISTANT/SYSTEM 三种角色
```

### 第二阶段：理解 Agent 循环（1 小时）

```
agent/AgentEngine.java             ← 核心循环：chat() → executeLoop()
agent/ConversationMemory.java      ← 消息存储 + 自动压缩触发
model/ModelAdapter.java            ← 模型调用接口
tool/Tool.java                     ← 工具接口
tool/ToolOrchestrator.java         ← 工具调度（并发/顺序分区）
tool/ToolRegistry.java             ← 工具注册表
```

### 第三阶段：理解模型适配（1 小时）

```
model/RuleBasedModelAdapter.java         ← 规则引擎（无需 API，测试用）
model/llm/LlmBackedModelAdapter.java     ← 真实 API 调用
model/llm/LlmConversationMapper.java     ← 内部协议 → API 格式转换（关键！）
model/llm/AnthropicProviderClient.java   ← Anthropic HTTP 客户端 + SSE 流解析
model/llm/ModelAdapterFactory.java       ← 工厂组装
```

### 第四阶段：理解上下文压缩（1 小时）

```
compact/TokenEstimator.java              ← Token 估算（字符级启发式）
compact/MicroCompactor.java              ← Level 1：清理过期工具结果
compact/SessionMemoryCompactor.java      ← Level 2：会话内存替代早期消息
compact/FullCompactor.java               ← Level 3：结构化摘要
compact/SessionMemory.java               ← 10 段式 Markdown 会话记忆
compact/CompactResult.java               ← 压缩结果 record
```

### 第五阶段：理解工具实现（1 小时）

```
tool/impl/ReadFileTool.java              ← 文件读取
tool/impl/WriteFileTool.java             ← 文件写入
tool/impl/ListFilesTool.java             ← 文件列表
tool/impl/AgentTool.java                 ← 子 Agent 创建（最复杂的工具）
tool/impl/SendMessageTool.java           ← Agent 间通信
tool/impl/ToolSearchTool.java            ← 工具搜索（延迟加载）
```

### 第六阶段：高级子系统（每个 1-2 小时）

```
agent/SubAgentRunner.java                ← 子 Agent 线程隔离
mcp/transport/McpTransport.java          ← MCP 传输接口
mcp/client/McpClient.java               ← MCP 客户端
mcp/client/McpConnectionManager.java     ← 多服务器连接管理
tool/streaming/StreamingToolExecutor.java← 流式工具执行
skill/SkillLoader.java                   ← Skill 文件解析
prompt/SystemPromptBuilder.java          ← 系统提示词模块化构建
```

---

## 4. 项目结构全景

### 4.1 包结构与职责

```
com.co.claudecode.demo/
├── InteractiveApplication.java     # REPL 入口
├── DemoApplication.java            # 单任务入口
│
├── message/                        # 消息协议层（8 个文件）
│   ├── ContentBlock.java           #   sealed interface — 5 种内容块
│   ├── TextBlock.java              #   纯文本
│   ├── ToolCallBlock.java          #   工具调用（tool_use）
│   ├── ToolResultBlock.java        #   工具结果（tool_result）
│   ├── SummaryBlock.java           #   压缩摘要
│   ├── ToolReferenceBlock.java     #   工具引用（延迟加载）
│   ├── ConversationMessage.java    #   消息记录（role + blocks）
│   └── MessageRole.java            #   角色枚举
│
├── agent/                          # Agent 引擎（11 个文件）
│   ├── AgentEngine.java            #   核心循环
│   ├── ConversationMemory.java     #   对话记忆 + 三级压缩
│   ├── SubAgentRunner.java         #   子 Agent 执行器
│   ├── AgentDefinition.java        #   Agent 类型定义
│   ├── AgentRegistry.java          #   Agent 注册表
│   ├── BuiltInAgents.java          #   内置 Agent 定义
│   ├── AgentTask.java              #   Agent 任务状态
│   ├── AgentTaskRegistry.java      #   全局任务注册表
│   ├── AgentResult.java            #   执行结果
│   ├── AsyncAgentHandle.java       #   异步句柄
│   └── SimpleContextCompactor.java #   简单压缩器（向后兼容）
│
├── model/                          # 模型适配层（2+11 个文件）
│   ├── ModelAdapter.java           #   核心接口
│   ├── RuleBasedModelAdapter.java  #   规则引擎（测试用）
│   └── llm/                        #   LLM 实现
│       ├── LlmBackedModelAdapter.java    # 真实 API 适配器
│       ├── LlmConversationMapper.java    # 消息格式映射器（关键！）
│       ├── AnthropicProviderClient.java  # Anthropic API 客户端
│       ├── OpenAiProviderClient.java     # OpenAI API 客户端
│       ├── AbstractLlmProviderClient.java# 公共基类
│       ├── LlmProviderClient.java        # 提供者接口
│       ├── ModelAdapterFactory.java      # 工厂类
│       ├── LlmRequest/Response.java      # 请求/响应 record
│       ├── LlmMessage.java              # API 消息格式
│       ├── ModelProvider.java            # 提供者枚举
│       ├── ModelRuntimeConfig.java       # 运行时配置
│       └── StreamCallback.java           # 流式回调接口
│
├── tool/                           # 工具系统（10+10+4 个文件）
│   ├── Tool.java                   #   工具接口
│   ├── ToolMetadata.java           #   工具元数据 record
│   ├── ToolRegistry.java           #   工具注册表
│   ├── ToolOrchestrator.java       #   工具调度器
│   ├── ToolExecutionContext.java    #   执行上下文
│   ├── ToolResult.java             #   执行结果
│   ├── PermissionPolicy.java       #   权限策略接口
│   ├── PermissionDecision.java     #   权限决定
│   ├── WorkspacePermissionPolicy.java # 工作区权限实现
│   ├── ToolSearchUtils.java        #   延迟加载工具
│   │
│   ├── impl/                       #   工具实现（10 个文件）
│   │   ├── ReadFileTool.java
│   │   ├── WriteFileTool.java
│   │   ├── ListFilesTool.java
│   │   ├── AgentTool.java          #     最复杂——创建子 Agent
│   │   ├── SendMessageTool.java    #     Agent 间消息发送
│   │   ├── ToolSearchTool.java     #     工具搜索/发现
│   │   ├── TaskCreateTool.java
│   │   ├── TaskGetTool.java
│   │   ├── TaskListTool.java
│   │   └── TaskUpdateTool.java
│   │
│   └── streaming/                  #   流式工具执行（4 个文件）
│       ├── StreamingToolExecutor.java
│       ├── StreamingToolCallback.java
│       └── StreamingToolConfig.java
│
├── compact/                        # 上下文压缩系统（8 个文件）
│   ├── TokenEstimator.java         #   Token 估算器
│   ├── MicroCompactor.java         #   Level 1 微型压缩
│   ├── SessionMemoryCompactor.java #   Level 2 会话内存压缩
│   ├── FullCompactor.java          #   Level 3 完整压缩
│   ├── SessionMemory.java          #   会话内存（10段模板）
│   ├── MicroCompactConfig.java     #   微压缩配置
│   ├── CompactResult.java          #   压缩结果 record
│   └── CompactType.java            #   压缩类型枚举
│
├── mcp/                            # MCP 协议集成（9+2+4+4+6 个文件）
│   ├── McpConfigLoader.java        #   配置文件发现与解析
│   ├── McpNameUtils.java           #   工具命名规范
│   ├── McpPermissionPolicy.java    #   MCP 权限策略
│   ├── McpServerConfig.java        #   服务器配置
│   ├── McpServerConnection.java    #   连接状态
│   ├── McpConnectionState.java     #   连接状态枚举
│   ├── McpTransportType.java       #   传输类型枚举
│   ├── McpToolInfo.java            #   工具信息
│   ├── McpResourceInfo.java        #   资源信息
│   │
│   ├── auth/                       #   认证（2 个文件）
│   │   ├── JwtTokenProvider.java   #     JWT + OAuth2 认证
│   │   └── McpAuthConfig.java      #     OAuth 配置
│   │
│   ├── protocol/                   #   JSON-RPC 协议（4 个文件）
│   │   ├── SimpleJsonParser.java   #     零依赖 JSON 解析器
│   │   ├── JsonRpcRequest.java
│   │   ├── JsonRpcResponse.java
│   │   └── JsonRpcError.java
│   │
│   ├── transport/                  #   传输层（4 个文件）
│   │   ├── McpTransport.java       #     传输接口
│   │   ├── StdioTransport.java     #     子进程 stdin/stdout
│   │   ├── StreamableHttpTransport.java # Streamable HTTP
│   │   └── LegacySseTransport.java #     经典 SSE 双通道
│   │
│   ├── client/                     #   客户端（4 个文件）
│   │   ├── McpClient.java          #     MCP 客户端
│   │   ├── McpConnectionManager.java#    多服务器管理
│   │   ├── McpInitResult.java
│   │   └── McpResourceContent.java
│   │
│   └── tool/                       #   工具桥接（6 个文件）
│       ├── McpToolBridge.java      #     MCP→Java Tool 直通适配
│       ├── MappedMcpTool.java      #     名称映射+参数注入适配
│       ├── ToolMapping.java        #     映射规则 record
│       ├── MappedToolRegistry.java #     映射定义工厂
│       ├── ListMcpResourcesTool.java
│       └── ReadMcpResourceTool.java
│
├── skill/                          # Skill 系统（5 个文件）
│   ├── SkillDefinition.java        #   Skill 定义 record
│   ├── SkillLoader.java            #   Skill 文件扫描/解析
│   ├── SkillRegistry.java          #   Skill 注册表
│   ├── SkillTool.java              #   Skill 执行工具
│   └── package-info.java
│
├── task/                           # 任务管理（3 个文件）
│   ├── Task.java                   #   任务 record（不可变 + wither）
│   ├── TaskStatus.java             #   状态枚举
│   └── TaskStore.java              #   线程安全任务存储
│
└── prompt/                         # 系统提示词（3 个文件）
    ├── SystemPromptBuilder.java    #   提示词组装器
    ├── SystemPromptSections.java   #   静态提示词段落
    └── EnvironmentInfo.java        #   运行时环境信息采集
```

---

## 5. 核心数据流：一次完整对话的生命周期

理解这个项目最重要的一步是搞清楚 **一条用户消息从输入到最终回复经历了什么**。下面以交互式 REPL 模式为例，走一遍完整生命周期。

### 5.1 时序图（ASCII）

```
用户输入 "帮我读 README.md"
  │
  ▼
InteractiveApplication.main()
  │  engine.chat(userInput, eventSink)
  ▼
AgentEngine.chat()
  │  memory.append(ConversationMessage.user(userInput))
  │  return executeLoop(eventSink)
  ▼
AgentEngine.executeLoop()   ←──────── 主循环入口
  │
  │  ┌─── TURN 1 ──────────────────────────────────┐
  │  │ consumePendingMessages()                      │  ← 检查其他Agent发来的消息
  │  │ modelAdapter.nextReply(memory.snapshot(), ctx) │  ← 调用LLM
  │  │   │                                           │
  │  │   ▼                                           │
  │  │ LlmBackedModelAdapter                         │
  │  │   │ LlmConversationMapper.toRequest(...)      │  ← 内部协议→API格式
  │  │   │ providerClient.generateStream(request)    │  ← HTTP + SSE
  │  │   │ parse SSE → ConversationMessage           │  ← 解析流式响应
  │  │   ▼                                           │
  │  │ assistantMessage = {                          │
  │  │   role: ASSISTANT,                            │
  │  │   blocks: [                                   │
  │  │     ToolCallBlock("read_file", {path:"README.md"}) │
  │  │   ]                                           │
  │  │ }                                             │
  │  │                                               │
  │  │ memory.appendAndCompact(assistantMessage)     │  ← 追加+检查压缩
  │  │ toolCalls = assistantMessage.toolCalls()      │  ← 提取工具调用
  │  │ toolCalls.isEmpty() == false → 继续循环        │
  │  │                                               │
  │  │ toolOrchestrator.execute(toolCalls, ctx)      │  ← 执行工具
  │  │   │ permissionPolicy.evaluate(tool, call)     │  ← 权限检查
  │  │   │ tool.execute(input, ctx)                  │  ← ReadFileTool 读文件
  │  │   │ → ToolResult("# README\n...")             │
  │  │   ▼                                           │
  │  │ toolResults = [ ConversationMessage.toolResult(│
  │  │   ToolResultBlock(id, "read_file", false, content) │
  │  │ )]                                            │
  │  │                                               │
  │  │ for(toolResult): memory.appendAndCompact(...)  │  ← 追加结果+检查压缩
  │  └───────────────────────────────────────────────┘
  │
  │  ┌─── TURN 2 ──────────────────────────────────┐
  │  │ modelAdapter.nextReply(memory.snapshot(), ctx) │
  │  │   → assistantMessage = {                      │
  │  │       role: ASSISTANT,                        │
  │  │       blocks: [ TextBlock("README 内容如下…")]  │
  │  │     }                                         │
  │  │ toolCalls.isEmpty() == true → 循环退出          │
  │  └───────────────────────────────────────────────┘
  │
  ▼
return assistantMessage → 显示给用户
```

### 5.2 关键节点详解

#### 节点 1：消息创建与存储

```java
// AgentEngine.chat() — 第 109 行
memory.append(ConversationMessage.user(userInput));
```

`ConversationMessage.user()` 是工厂方法，内部创建 `new ConversationMessage(MessageRole.USER, List.of(new TextBlock(text)))`。消息立即追加到 `ConversationMemory` 的 `ArrayList<ConversationMessage>` 中。

#### 节点 2：对话快照 → LLM API

```java
// AgentEngine.executeLoop() — 第 137 行
assistantMessage = modelAdapter.nextReply(memory.snapshot(), context);
```

`memory.snapshot()` 返回 `List.copyOf(messages)`（不可变副本），然后：
1. `LlmConversationMapper.toRequest()` 遍历所有消息
2. 提取 SYSTEM 消息中的文本 → 组装成系统提示词
3. 将 USER/ASSISTANT 消息映射为 `LlmMessage`
4. **关键**：`ensureToolPairing()` 过滤掉孤立的 `tool_result`（防止 Anthropic API 400）
5. 构建工具 schema 列表（支持 `deferLoading` 标志）

#### 节点 3：工具调度与执行

```java
// ToolOrchestrator.execute() — 第 47 行
public List<ConversationMessage> execute(List<ToolCallBlock> calls, ...)
```

调度器将工具调用按 `concurrencySafe` 标志分批：
- **并发安全的工具**（如 `read_file`, `list_files`）：合并为一个 batch，通过 `ExecutorService.invokeAll()` 并行执行
- **非并发安全的工具**（如 `write_file`, `agent`）：逐个顺序执行

每个工具执行前都经过 `PermissionPolicy.evaluate()` 权限检查。

#### 节点 4：追加结果与压缩检查

```java
// AgentEngine.executeLoop() — 第 166 行
CompactResult compactAfterTool = memory.appendAndCompact(toolResult);
```

每次追加消息后，`ConversationMemory.maybeCompact()` 会估算当前总 Token 数。如果超过阈值 (170,616)，触发三级压缩链：Level 1 微压缩 → Level 2 会话内存 → Level 3 完整摘要。

#### 节点 5：循环终止

当模型返回的消息不包含任何 `ToolCallBlock` 时，循环退出，返回最终的文本回复。如果达到 `maxTurns` 上限（默认 12），返回兜底消息。

---

## 6. 子系统详解

### 6.1 消息协议层 (message/)

消息协议层是整个系统的 **数据基石**——所有子系统都通过它交换信息。理解它才能读懂其余代码。

#### 核心设计：统一消息流

原始 TS 版把文本、tool_use、tool_result 都放进统一消息流。Java 版保留了这个设计：

```java
// ContentBlock.java — 整个系统的类型基石
public sealed interface ContentBlock
    permits SummaryBlock, TextBlock, ToolCallBlock, ToolResultBlock, ToolReferenceBlock {
    String renderForModel();
}
```

`sealed interface` 是 Java 17 的密封接口特性——**编译器保证只有这 5 个实现类**。这意味着：
- 任何 `switch(block)` 如果遗漏了某个类型，编译器会警告
- 新增消息类型必须修改 `permits` 列表，强制审查所有 switch 点
- 与 TS 的 `union type` (`TextBlock | ToolUseBlock | ...`) 等价，但有编译期保障

#### 五种内容块

| 类型 | 职责 | 关键字段 |
|------|------|----------|
| `TextBlock` | 纯文本（用户输入/模型回复） | `text` |
| `ToolCallBlock` | 模型发出的工具调用请求 | `id`, `toolName`, `input` (Map) |
| `ToolResultBlock` | 工具执行返回的结果 | `toolUseId`, `toolName`, `error`, `content` |
| `SummaryBlock` | 上下文压缩产生的摘要 | `summary` |
| `ToolReferenceBlock` | 延迟加载发现的工具引用 | `toolName` |

注意 `ToolCallBlock` 和 `ToolResultBlock` 通过 `id`/`toolUseId` 配对——这个配对关系在 Anthropic API 中是强制要求的，如果 `tool_result` 找不到对应的 `tool_use`，API 会返回 400 错误。

#### ConversationMessage：消息记录

```java
public record ConversationMessage(MessageRole role, List<ContentBlock> blocks) {
    // 防御性复制的 compact 构造器
    public ConversationMessage {
        role = role == null ? MessageRole.USER : role;
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }
}
```

**关键设计点：**

1. **`record` + 防御性复制**：compact 构造器中 `null → 默认值` + `List.copyOf()` 确保不可变性。外部传入的列表被复制一份，后续修改原列表不会影响消息。

2. **工厂方法代替 `new`**：
   ```java
   ConversationMessage.user("帮我读 README")     // 创建用户消息
   ConversationMessage.assistant("好的", toolCalls) // 创建助手消息（可带工具调用）
   ConversationMessage.toolResult(resultBlock)     // 创建工具结果消息
   ConversationMessage.system("你是一个助手")       // 创建系统消息
   ```

3. **查询方法基于 `instanceof` 过滤**：
   ```java
   // 从 blocks 中提取所有 ToolCallBlock
   public List<ToolCallBlock> toolCalls() {
       return blocks.stream()
               .filter(ToolCallBlock.class::isInstance)
               .map(ToolCallBlock.class::cast)
               .toList();
   }
   ```
   同理有 `toolResults()`、`toolReferences()`、`plainText()`。这比维护多个字段更灵活——一条消息可以同时包含文本和工具调用。

4. **`renderForModel()`**：将消息序列化为可读格式（`role: content`），主要用于规则引擎和调试。

#### MessageRole 枚举

```java
public enum MessageRole {
    SYSTEM("System"),
    USER("Human"),
    ASSISTANT("Assistant");

    private final String label;
}
```

三种角色。注意 `USER` 的 label 是 `"Human"` 而非 `"User"`——这与 Anthropic API 的 Human/Assistant 命名约定保持一致。

---

### 6.2 Agent 引擎 (agent/)

Agent 引擎是系统的 **控制中心**——它驱动"消息 → 工具 → 消息"的闭环。

#### AgentEngine：主循环

`AgentEngine` 的核心是 `executeLoop()` 方法，一个标准的 while 循环：

```java
private ConversationMessage executeLoop(Consumer<String> eventSink) {
    for (int turn = 1; turn <= maxTurns; turn++) {
        // 1. 检查取消信号（子 Agent）
        if (agentTask != null && agentTask.isAborted()) { return aborted; }

        // 2. 消费来自其他 Agent 的消息
        consumePendingMessages(eventSink);

        // 3. 调用模型（流式 vs 经典）
        assistantMessage = streamingEnabled
            ? callModelWithStreamingTools(eventSink)
            : modelAdapter.nextReply(memory.snapshot(), context);

        // 4. 追加并检查压缩
        memory.appendAndCompact(assistantMessage);

        // 5. 提取工具调用，无调用则退出
        if (toolCalls.isEmpty()) return assistantMessage;

        // 6. 执行工具（流式路径 vs 经典路径）
        toolResults = streamingToolExecutor != null
            ? streamingToolExecutor.awaitAllResults()
            : toolOrchestrator.execute(toolCalls, context, eventSink);

        // 7. 追加工具结果
        for (toolResult : toolResults) memory.appendAndCompact(toolResult);
    }
    return fallback; // 达到 maxTurns 上限
}
```

**三种构造器（Telescoping Constructors）**：

| 参数数 | 用途 | 额外字段 |
|--------|------|----------|
| 5 个 | 主 Agent（向后兼容） | — |
| 7 个 | 子 Agent | `agentId`, `agentTask` |
| 9 个 | 完整版（含流式） | + `streamCallback`, `toolRegistry` |

#### ConversationMemory：三级自适应压缩

`ConversationMemory` 不仅仅是消息列表——它内置了三级压缩引擎：

```
Token 预算公式:
autoCompactThreshold = contextWindowTokens - maxOutputTokens - autoCompactBuffer
默认: 200,000 - 16,384 - 13,000 = 170,616 tokens
```

每次调用 `appendAndCompact()` 时：
1. 追加消息到列表
2. 估算当前总 Token 数（`TokenEstimator`）
3. 如果超过阈值 → 触发 `executeCompaction()`

压缩级联链：

```
Level 1: MicroCompactor
├── 清理超过 keepRecent 条的旧 ToolResultBlock
├── 将过期结果替换为 "[Result truncated]"
└── 成功？→ 返回 | 仍超标？→ 进入 Level 2

Level 2: SessionMemoryCompactor
├── 用 SessionMemory 内容替代早期消息
├── 保留最近 N 条消息（尾部保护）
└── 成功？→ 返回 | 仍超标？→ 进入 Level 3

Level 3: FullCompactor
├── 生成结构化摘要（5 段：用户意图/工具轨迹/关键决策/未完成工作/最近文件）
├── 替换所有被压缩消息为 SummaryBlock
└── 触发熔断器：连续失败 3 次则停止尝试
```

**关键约束：tool_use/tool_result 配对保护**。压缩过程中绝不能拆散配对，在三层防御：
- `ConversationMemory.adjustTailForToolPairing()` — 压缩时向后调整切割点
- `SessionMemoryCompactor.adjustCutPointForToolPairing()` — 会话内存压缩的切割点调整
- `LlmConversationMapper.ensureToolPairing()` — 发送给 API 前最终兜底过滤

#### SubAgentRunner：子 Agent 隔离

TS 原版通过子进程实现 Agent 隔离，Java 版用线程级隔离达到类似效果：

```java
private String executeAgent(...) {
    // 1. 过滤工具集（按 AgentDefinition）
    List<Tool> filteredTools = allTools.stream()
            .filter(tool -> agentDef.isToolAllowed(tool.metadata().name()))
            .toList();

    // 2. 创建独立 ToolOrchestrator
    // 3. 创建独立 ConversationMemory
    // 4. 注入系统提示词
    // 5. 创建独立 AgentEngine 并运行
}
```

每个子 Agent 拥有：
- **独立的 `ConversationMemory`**（24 条消息上限，12 条压缩阈值）
- **过滤后的 `ToolRegistry`**（递归保护：子 Agent 无法获得 `AgentTool` 和 `SendMessageTool`）
- **独立的 `AgentEngine`** 实例

两种运行模式：
- `runSync()` — 当前线程阻塞执行
- `runAsync()` — 线程池异步执行，返回 `AsyncAgentHandle`（含 `CompletableFuture`）

#### Agent 间通信

```
Agent A                          Agent B
   │                                │
   │  SendMessageTool.execute()     │
   │ ─────────────────────────────> │
   │  agentTask.enqueueMessage()    │  pendingMessages (ConcurrentLinkedQueue)
   │                                │
   │                          每轮 executeLoop() 开始时
   │                          consumePendingMessages()
   │                                │  → drainMessages()
   │                                │  → memory.append(user("[Message from another agent] ..."))
```

---

### 6.3 模型适配层 (model/)

模型层的设计目标是 **把"如何思考"与"如何调用 API"彻底解耦**。Agent 循环只依赖 `ModelAdapter` 接口，完全不感知底层是 Anthropic、OpenAI 还是规则引擎。

#### 接口分层

```
ModelAdapter (agent 层依赖)
├── RuleBasedModelAdapter    — 确定性规则引擎（测试用）
└── LlmBackedModelAdapter    — 真实 LLM API 调用
       │
       └── LlmProviderClient (provider 差异抽象)
              ├── AnthropicProviderClient  — Anthropic Messages API
              └── (可扩展 OpenAI 等)
```

**为什么分两层？** `ModelAdapter` 抽象的是"对话 → 回复"的整体行为；`LlmProviderClient` 抽象的是"请求组装、HTTP 调用、响应解析"的 provider 差异。这样 `LlmBackedModelAdapter` 可以在中间做通用逻辑（工具过滤、延迟加载、流式分发），而 provider-specific 的细节被推到最底层。

#### ModelAdapter 接口

```java
public interface ModelAdapter {
    ConversationMessage nextReply(List<ConversationMessage> conversation,
                                  ToolExecutionContext context);

    // 带流式回调的重载（默认委托到上面的方法）
    default ConversationMessage nextReply(List<ConversationMessage> conversation,
                                          ToolExecutionContext context,
                                          StreamCallback callback) {
        return nextReply(conversation, context);
    }
}
```

两个关键设计点：
1. **`default` 方法实现向后兼容**——`RuleBasedModelAdapter` 只需实现 2 参方法，流式回调对它透明
2. **返回的是 `ConversationMessage`**，不是裸字符串——模型回复可能同时包含文本和工具调用

#### 内部消息协议转换

`LlmConversationMapper` 是模型层最关键的类——负责把内部消息协议转换成 API 格式：

```
ConversationMessage (内部)          LlmRequest (API)
┌─────────────────────┐            ┌──────────────────────┐
│ SYSTEM: 角色定义     │  ───────> │ systemPrompt (拼接)   │
│ USER: 用户输入       │  ───────> │ LlmMessage(user,TEXT) │
│ ASSISTANT: 文本+工具 │  ───────> │ LlmMessage(assistant, │
│                     │           │   TOOL_CALLS, [...])  │
│ USER: 工具结果       │  ───────> │ LlmMessage(user,      │
│ (ToolResultBlock)   │           │   TOOL_RESULT, id=..) │
│ SYSTEM: 摘要        │  ───────> │ systemPrompt += 摘要   │
└─────────────────────┘            └──────────────────────┘
```

**三种 LlmMessage 类型**：
- `TEXT` — 普通文本消息（大多数用户/助手消息）
- `TOOL_CALLS` — assistant 消息中包含工具调用（可同时有文本）
- `TOOL_RESULT` — 工具执行结果，必须携带 `toolUseId` 以与 `tool_use` 配对

**双集合 `toRequest()` 设计**——这是延迟加载的关键接口：

```java
public LlmRequest toRequest(List<ConversationMessage> conversation,
                             String modelName,
                             Collection<Tool> filteredTools,  // 实际发送 schema 的子集
                             Collection<Tool> allTools) {     // 全量（决定 deferLoading 标志）
```

`filteredTools` 由 `ToolSearchUtils.filterToolsForApi()` 过滤——只包含非延迟工具 + 已被 ToolSearch 发现的延迟工具。`allTools` 用于给每个 schema 标记 `deferLoading=true/false`，告诉 provider 哪些工具的 schema 可以被缓存。

**`ensureToolPairing()` 两遍扫描算法**：

```java
private List<LlmMessage> ensureToolPairing(List<LlmMessage> messages) {
    // 第一遍：收集所有已出现的 tool_use id
    Set<String> availableToolUseIds = new HashSet<>();
    for (LlmMessage msg : messages) {
        if (msg.type() == Type.TOOL_CALLS) {
            for (ToolCallBlock tc : msg.toolCalls()) {
                availableToolUseIds.add(tc.id());
            }
        }
    }
    // 第二遍：过滤掉找不到配对的 tool_result
    // ...只保留 toolUseId 在 availableToolUseIds 中的
}
```

这是 tool_use/tool_result 配对保护的最终防线——即使上游压缩逻辑有遗漏，这里也会兜底过滤掉孤立的 tool_result。

#### LlmBackedModelAdapter：中间层协调

```java
private ConversationMessage doNextReply(..., StreamCallback callback) {
    Collection<Tool> allTools = toolRegistry.allTools();

    // 1. 延迟加载过滤
    List<Tool> filteredTools = ToolSearchUtils.filterToolsForApi(allTools, conversation);

    // 2. 映射为 API 请求（双集合模式）
    LlmRequest request = mapper.toRequest(conversation, modelName, filteredTools, allTools);

    // 3. 流式 vs 非流式分发
    LlmResponse response = (callback != null)
        ? providerClient.generateStream(request, callback)
        : providerClient.generate(request);

    // 4. 转回内部消息
    return ConversationMessage.assistant(response.text(), toolCalls);
}
```

注意 `nextReply()` 的三参重载如何支持 callback 覆盖——流式工具执行时，`AgentEngine` 传入 `StreamingToolCallback` 替代默认的 `StreamCallback`。

#### AnthropicProviderClient：零依赖 HTTP 实现

整个 HTTP 调用层（请求构造、SSE 解析、JSON 序列化/反序列化）都是手写的，没有 Jackson/Gson 依赖。核心方法：

| 方法 | 职责 |
|------|------|
| `generate()` | 非流式：发送 POST，等待完整响应 |
| `generateStream()` | 流式 SSE：逐行读取 `data:` 事件，回调 `StreamCallback` |
| `buildRequestBody()` | 手工拼接 JSON 请求体（含 tools schema） |
| `parseSseStream()` | 解析 SSE 事件流，检测 `content_block_stop` 通知流式工具执行 |
| `buildEndpoint()` | 构建 API 端点 URL（处理 baseUrl 是否已包含 `/v1`） |

流式关键流程：
```
SSE 事件流
  │
  ├─ content_block_delta (type=text_delta)
  │    → callback.onTextToken(text)  → 实时输出
  │
  ├─ content_block_stop (tool_use block)
  │    → 如果 callback instanceof StreamingToolCallback
  │    → callback.onToolUseComplete(toolCall)  → 立即调度执行
  │
  └─ message_stop
       → 组装最终 LlmResponse 返回
```

#### 数据记录类型

模型层使用多个 `record` 作为数据传输对象，全部采用防御性复制模式：

```java
// LlmRequest — 发往 provider 的请求
public record LlmRequest(String systemPrompt, List<LlmMessage> messages,
                          String modelName, List<ToolSchema> tools) {
    public LlmRequest { tools = tools == null ? List.of() : List.copyOf(tools); }
}

// LlmResponse — provider 返回的响应
public record LlmResponse(String text, List<ToolCallData> toolCalls) {
    public LlmResponse { toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls); }
}
```

每个 record 的 compact 构造器都保证 `null → 空默认值` + `List.copyOf()` 不可变。

---

### 6.4 工具系统 (tool/)

工具系统的设计哲学是 **"工具只管能力，治理和调度放在外层"**。每个工具实现只关心"给我参数，返回结果"，权限检查和并发控制由 `ToolOrchestrator` 统一处理。

#### Tool 接口

```java
public interface Tool {
    ToolMetadata metadata();                              // 元数据（名称、描述、治理标志）
    default void validate(Map<String, String> input) {}   // 可选参数校验
    ToolResult execute(Map<String, String> input,         // 核心执行
                       ToolExecutionContext context) throws Exception;
}
```

设计极简——只有 3 个方法，其中 `validate()` 还有默认空实现。工具不需要关心：
- 是否有权限执行（`PermissionPolicy` 负责）
- 是否可以并发（`ToolOrchestrator` 看 `concurrencySafe` 标志）
- schema 如何发送给模型（`LlmConversationMapper` 负责）

#### ToolMetadata：12 字段记录

```java
public record ToolMetadata(
    String name,              // 工具名（如 "read_file"）
    String description,       // 描述（发送给模型的）
    boolean readOnly,         // 只读？
    boolean concurrencySafe,  // 可并发？
    boolean destructive,      // 破坏性操作？
    PathDomain pathDomain,    // NONE / WORKSPACE / ARTIFACT
    String pathInputKey,      // 路径参数名（如 "file_path"）
    List<ParamInfo> params,   // 参数 schema 列表
    // 延迟加载字段
    boolean isMcp,            // 是否 MCP 外部工具
    boolean shouldDefer,      // 显式延迟加载
    boolean alwaysLoad,       // 始终加载（不延迟）
    String searchHint         // ToolSearch 搜索提示词
) { ... }
```

**三个构造器（Telescoping 模式）**：7 参（基础）→ 8 参（+params）→ 12 参（+延迟加载）。每增加一个能力，旧代码不需要修改。

**治理标志的含义**：

| 标志 | 作用 | 使用者 |
|------|------|--------|
| `readOnly` | 只读工具不能修改文件系统 | `PermissionPolicy` |
| `concurrencySafe` | 允许与其他工具并发执行 | `ToolOrchestrator.partition()` |
| `destructive` | 破坏性操作需要额外确认 | `PermissionPolicy` |
| `pathDomain` | 路径限制在工作区/产物区 | `WorkspacePermissionPolicy` |

#### ToolOrchestrator：智能分批调度

`ToolOrchestrator` 不是简单地"收到调用就执行"——它 **按连续的 concurrencySafe 属性分批**：

```
模型返回 3 个工具调用:
  [read_file(safe), list_files(safe), write_file(unsafe)]

partition() 分批:
  Batch 1: [read_file, list_files]  → concurrent=true  → invokeAll() 并发
  Batch 2: [write_file]             → concurrent=false → 顺序执行
```

```java
private List<Batch> partition(List<ToolCallBlock> calls) {
    for (ToolCallBlock call : calls) {
        boolean concurrencySafe = toolRegistry.require(call.toolName())
                .metadata().concurrencySafe();
        // 只有连续的 concurrencySafe=true 才合并到同一批
        if (lastBatch != null && lastBatch.concurrentSafe() == concurrencySafe
                && concurrencySafe) {
            lastBatch.calls().add(call);
        } else {
            batches.add(new Batch(concurrencySafe, new ArrayList<>(call)));
        }
    }
}
```

**注意**：只有 **连续的** `concurrencySafe=true` 工具才合并。如果中间插入一个非并发安全的工具，会打断批次。这保证了执行顺序语义。

#### 执行流程

```
ToolOrchestrator.execute(calls, context, eventSink)
  │
  ├── partition(calls)                    // 分批
  │
  ├── for each Batch:
  │   ├── concurrentSafe?
  │   │   ├── YES → executeConcurrent()   // ExecutorService.invokeAll()
  │   │   └── NO  → 逐个 executeSingle()
  │   │
  │   └── executeSingle(call):
  │       ├── toolRegistry.require(name)  // 查找工具
  │       ├── tool.validate(input)        // 参数校验
  │       ├── permissionPolicy.evaluate() // 权限检查
  │       ├── tool.execute(input, ctx)    // 实际执行
  │       └── 包装为 ToolResultBlock      // 返回结果
  │
  └── 收集所有 ConversationMessage 结果
```

#### PermissionPolicy 与 ToolExecutionContext

```java
public interface PermissionPolicy {
    PermissionDecision evaluate(Tool tool, ToolCallBlock call, ToolExecutionContext context);
}
```

`ToolExecutionContext` 分离了两个概念：
- `workspaceRoot` — 读取范围（项目目录）
- `artifactRoot` — 写入范围（输出目录，可能不同）

`WorkspacePermissionPolicy` 根据 `pathDomain` 检查路径：
- `WORKSPACE` → 路径必须在 `workspaceRoot` 下
- `ARTIFACT` → 路径必须在 `artifactRoot` 下
- `NONE` → 不做路径检查

#### ToolRegistry：名称查找

```java
public final class ToolRegistry {
    private final Map<String, Tool> toolsByName = new LinkedHashMap<>();

    public Tool require(String toolName) {
        Tool tool = toolsByName.get(toolName);
        if (tool == null) throw new IllegalArgumentException("Unknown tool: " + toolName);
        return tool;
    }

    public Collection<Tool> allTools() { return toolsByName.values(); }
    public int size() { return toolsByName.size(); }
}
```

使用 `LinkedHashMap` 保持插入顺序——这对 schema 发送给模型时的顺序一致性很重要。`require()` 而非 `get()` 的命名暗示"找不到就抛异常"。

### 6.5 上下文压缩系统 (compact/)

Agent 循环最大的限制是**上下文窗口**——Claude 有 200K token 的窗口，但长时间对话和大量工具调用会迅速耗尽。`compact/` 包实现了一套**三级自适应压缩系统**，对应 TS 原版 `autoCompact.ts` 的 `hWK()` 决策链。

#### 整体架构

```
                    ConversationMemory
                    ┌──────────────────────┐
                    │ appendAndCompact()   │
                    │   ↓                  │
                    │ maybeCompactTokenBased()
                    │   ↓ 超过阈值?        │
                    │   ↓ YES              │
                    │ executeCompaction()   │
                    │   │                  │
                    │   ├─ Level 1: MicroCompactor
                    │   │   压缩后仍超阈值? │
                    │   │   ↓ YES          │
                    │   ├─ Level 2: SessionMemoryCompactor
                    │   │   不适用?         │
                    │   │   ↓ YES          │
                    │   └─ Level 3: FullCompactor
                    │                      │
                    │ 全部失败 → 熔断计数+1  │
                    │ (MAX_CONSECUTIVE=3)   │
                    └──────────────────────┘
```

**核心阈值计算**（与 TS 对齐）：

```
autoCompactThreshold = contextWindowTokens - maxOutputTokens - autoCompactBuffer
默认: 200,000 - 16,384 - 13,000 = 170,616 tokens
```

当 `estimateCurrentTokens() >= autoCompactThreshold` 时触发压缩。

#### TokenEstimator：零依赖的 Token 估算

没有 tiktoken/sentencepiece 这样的 tokenizer 库，项目使用**字符级启发式估算**：

```java
public static int estimateTokens(String text) {
    int asciiChars = 0, cjkChars = 0, otherChars = 0;
    for (int i = 0; i < text.length(); i++) {
        char ch = text.charAt(i);
        if (ch <= 0x7F) asciiChars++;
        else if (isCjk(ch)) cjkChars++;
        else otherChars++;
    }
    // ASCII: ~4 chars/token; CJK: ~1.5 chars/token; Other: ~2 chars/token
    double tokens = asciiChars / 4.0 + cjkChars / 1.5 + otherChars / 2.0;
    return Math.max(1, (int) Math.ceil(tokens));
}
```

| 字符类型 | 估算比率 | 覆盖范围 |
|---------|---------|---------|
| ASCII (`<= 0x7F`) | 4 字符/token | 英文、代码、标点 |
| CJK | 1.5 字符/token | 中日韩统一表意文字、平假名、片假名、韩文音节 |
| 其他 | 2 字符/token | emoji、阿拉伯文等 |

`isCjk()` 使用 JDK 的 `Character.UnicodeBlock` 检查 6 个 Unicode 块。精度不高但足以做预算判断——这和 TS 原版的"粗略上界"策略一致。

**三级重载**实现了从文本到消息列表的递进估算：

```java
estimateTokens(String text)                    // 单个文本
estimateTokens(ConversationMessage message)    // 单条消息（+4 role 标签开销）
estimateTokens(List<ConversationMessage>)      // 消息列表累加
estimateBlockTokens(ContentBlock block)        // 按 sealed interface 分派
```

`estimateBlockTokens` 是一个教科书级的 `instanceof` 模式匹配用例——`ContentBlock` 是 sealed interface，每种变体有不同的估算逻辑：
- `TextBlock` → 文本估算
- `ToolCallBlock` → 工具名 + JSON 开销 + 参数键值累加
- `ToolResultBlock` → 10 token 开销 + 内容估算
- `SummaryBlock` → 摘要文本估算

#### Level 1: MicroCompactor — 清理过期工具结果

**最轻量的压缩**——不改变消息结构，只替换旧的大型工具结果内容。

```java
// 可清理的工具类型
private static final Set<String> CLEARABLE_TOOLS = Set.of(
    "list_files", "read_file", "write_file"
);
```

**触发条件**：
1. 配置已启用（`MicroCompactConfig.enabled == true`）
2. 最后一条 assistant 消息距今超过 `gapThresholdMinutes`（默认 60 分钟）

**算法流程**：
1. 扫描所有消息，收集可清理的 `ToolResultBlock`（按出现顺序）
2. 保留最近 `keepRecent` 个（默认 5），其余标记为待清理
3. 将待清理的 `ToolResultBlock` 内容替换为 `"[Old tool result content cleared — saved ~N tokens]"`

```java
// 替换逻辑——注意保持 ToolResultBlock 结构完整
newBlocks.add(new ToolResultBlock(
    trb.toolUseId(), trb.toolName(), false,
    "[Old tool result content cleared — saved ~" + savedTokens + " tokens]"
));
```

关键设计：**保留 `toolUseId` 和 `toolName`**，只替换 `content`。这样 tool_use/tool_result 配对关系不被破坏。

`MicroCompactConfig` 是一个带验证的 compact constructor record：

```java
public record MicroCompactConfig(boolean enabled, long gapThresholdMinutes, int keepRecent) {
    public static final MicroCompactConfig DEFAULT = new MicroCompactConfig(false, 60, 5);
    public MicroCompactConfig {
        if (gapThresholdMinutes <= 0) gapThresholdMinutes = 60;
        if (keepRecent < 0) keepRecent = 5;
    }
}
```

#### Level 2: SessionMemoryCompactor — 零 API 调用压缩

**核心优势**：不需要调用 LLM API 生成摘要，用已有的 `SessionMemory` 内容替代被切割的早期消息。

**三个阈值常量**（与 TS 原版 `mp8` 对象对齐）：

| 常量 | 值 | 含义 |
|------|-----|------|
| `MIN_TOKENS` | 10,000 | 累积 token 最小值 |
| `MIN_TEXT_BLOCK_MESSAGES` | 5 | 文本消息最小数量 |
| `MAX_TOKENS` | 40,000 | 超过此值强制触发 |

**`calculateKeepIndex()` 算法**——从末尾向前扫描：

```java
static int calculateKeepIndex(List<ConversationMessage> messages) {
    int accumulatedTokens = 0;
    int textMessageCount = 0;
    for (int i = messages.size() - 1; i >= 0; i--) {
        accumulatedTokens += TokenEstimator.estimateTokens(messages.get(i));
        if (hasTextBlocks(msg)) textMessageCount++;
        if (accumulatedTokens >= MAX_TOKENS) return i;           // 强制停止
        if (accumulatedTokens >= MIN_TOKENS
            && textMessageCount >= MIN_TEXT_BLOCK_MESSAGES) return i; // 正常停止
    }
    return 0; // 整个对话都不够阈值
}
```

**双条件停止**是一个精心设计：只看 token 数可能保留过少消息（一条大消息就超限），只看消息数可能保留过多（很多短消息也占 token）。两个条件都满足时才停止。

**`adjustCutPointForToolPairing()`**——切割点修正：

```
消息序列: [sys] [user] [assistant+tool_use] [user+tool_result] [assistant] ...
                                              ↑ 如果切割点在这里
                                ↑ 需要向前移到这里（包含 tool_use 的 assistant）
```

如果切割点落在 `tool_result` 消息上，必须向前找到对应的 `assistant`（含 `tool_use`），把整个配对都保留或都丢弃。

**压缩后消息结构**：

```
[system header]
[SummaryBlock: "This session is being continued..." + sessionMemory内容]
[保留的尾部消息...]
```

#### SessionMemory：10 节 Markdown 模板

`SessionMemory` 使用规则提取（不调用 LLM），从对话消息中自动填充一个 10 节 Markdown 模板：

```
# Session Title        ← 第一条 user 消息（截断到 60 字符）
# Current State        ← 最近 3 条 assistant 消息
# Task Specification   ← 第一条 user 消息（截断到 500 字符）
# Files and Functions  ← read_file/write_file 工具调用的路径（最多 20 个）
# Workflow             ← 最近 15 个工具调用序列
# Errors & Corrections ← error=true 的 ToolResultBlock
# Codebase and System Documentation ← (未提取)
# Learnings            ← (未提取)
# Key Results          ← write_file 写入的路径
# Worklog              ← 前 20 个 assistant 轮次摘要
```

每节有 `MAX_SECTION_TOKENS = 2000` 上限，整体有 `MAX_TOTAL_TOKENS = 12000` 上限——`truncateSection()` 在最后一个换行符处截断以保持 Markdown 结构完整。

#### Level 3: FullCompactor — 结构化摘要

当 SessionMemory 不可用或不足以压缩到阈值以下时，执行**完整压缩**。

**提取 5 个维度的信息**：

| 维度 | 方法 | 提取逻辑 |
|------|------|---------|
| User Intent | `extractUserIntent()` | 最近 5 条 user 消息，排除 agent 转发消息 |
| Tool Call Trace | `extractToolTrace()` | 最近 20 条工具调用，含参数摘要和结果片段 |
| Key Decisions | `extractKeyDecisions()` | 最近 5 条有实质内容的 assistant 消息 |
| Unfinished Work | `extractUnfinishedWork()` | 最后一条 assistant 是否有未完成的 tool_call |
| Recently Accessed Files | `extractRecentFiles()` | 最近 5 个 read_file/write_file 路径 |

**输出格式**（与 TS 原版 `P18()` 函数对齐）：

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

Continue the conversation from where it left off without asking the user any further questions.
Resume directly — do not acknowledge the summary...
```

`fullCompact()` 方法接收 `protectedPrefix`（system 消息）和 `tailStart`（保留尾部起点），对中间部分执行摘要：

```java
public static CompactResult fullCompact(List<ConversationMessage> allMessages,
                                         int protectedPrefix, int tailStart) {
    List<ConversationMessage> toCompact = allMessages.subList(protectedPrefix, tailStart);
    ConversationMessage summaryMessage = compact(toCompact);
    // [protectedPrefix] + [summaryMessage] + [tailStart..end]
    ...
}
```

#### ConversationMemory：三级决策链编排

`ConversationMemory` 是三级压缩的**编排层**，核心是 `executeCompaction()` 方法：

```java
private CompactResult executeCompaction() {
    // Level 1: Micro — 清理过期工具结果
    CompactResult microResult = MicroCompactor.tryMicroCompact(messages, lastAssistantTime, microCompactConfig);
    if (microResult.didCompact()) {
        applyCompactResult(microResult);
        if (estimateCurrentTokens() < autoCompactThreshold) return microResult; // 够了
    }

    // Level 2: Session Memory — 零 API 压缩
    if (sessionMemory != null) {
        sessionMemory.extractMemoryFromConversation(messages); // 先更新内存
        CompactResult smResult = SessionMemoryCompactor.trySessionMemoryCompact(messages, sessionMemory);
        if (smResult.didCompact()) { applyCompactResult(smResult); return smResult; }
    }

    // Level 3: Full — 结构化摘要
    CompactResult fullResult = executeFullCompact();
    if (fullResult.didCompact()) {
        applyCompactResult(fullResult);
        if (sessionMemory != null) sessionMemory.extractMemoryFromConversation(messages);
        return fullResult;
    }

    consecutiveCompactFailures++; // 全部失败 → 熔断计数
    return null;
}
```

**熔断保护**（Circuit Breaker）：

```java
private static final int MAX_CONSECUTIVE_FAILURES = 3;

private CompactResult maybeCompactTokenBased() {
    if (currentTokens < autoCompactThreshold) return null;        // 未达阈值
    if (consecutiveCompactFailures >= MAX_CONSECUTIVE_FAILURES) return null; // 熔断
    return executeCompaction();
}
```

连续 3 次压缩全部失败（所有三级都未成功）后停止尝试，避免每次 `appendAndCompact()` 都执行无效的压缩计算。`applyCompactResult()` 成功时重置计数器。

**CompactResult 与 CompactType**——统一结果模型：

```java
public record CompactResult(
    List<ConversationMessage> messages,
    CompactType type,          // NONE | MICRO | SESSION_MEMORY | FULL
    int messagesRemoved,
    int tokensBeforeCompact,
    int tokensAfterCompact,
    String summary
) {
    public int tokensSaved() { return Math.max(0, tokensBeforeCompact - tokensAfterCompact); }
    public boolean didCompact() { return type != CompactType.NONE; }
}
```

三级压缩器都返回 `CompactResult`，上层通过 `didCompact()` 判断是否生效、通过 `type` 区分级别。这个统一接口是整个压缩系统可组合的关键。

#### 工具配对保护——三道防线

`tool_use/tool_result` 配对不一致会导致 Anthropic API 返回 400 错误，项目在三个层面设置了保护：

| 防线 | 位置 | 机制 |
|------|------|------|
| 第一道 | `ConversationMemory.adjustTailForToolPairing()` | Full Compact 切割点修正（递归处理，最多 10 次） |
| 第二道 | `SessionMemoryCompactor.adjustCutPointForToolPairing()` | Session Memory 切割点修正 |
| 第三道 | `LlmConversationMapper.ensureToolPairing()` | 发送 API 前最终过滤（两遍扫描） |

这种**纵深防御**确保即使某一层出现 bug，下一层仍然能拦截不匹配的消息。

### 6.6 MCP 协议集成（mcp/）

MCP（Model Context Protocol）是 Anthropic 推出的开放协议，让 AI Agent 能动态发现和调用外部服务器提供的工具和资源。本项目实现了完整的 MCP 客户端，**零外部依赖**——JSON-RPC 2.0、SSE 解析、JWT 签名全部手写。

#### 整体架构

```
┌─────────────────────────────────────────────────────┐
│                   AgentEngine                       │
│                       │                             │
│              ToolOrchestrator                       │
│              /        │        \                    │
│     内置 Tool    McpToolBridge   MappedMcpTool      │
│                       │              │              │
│              McpConnectionManager                   │
│              /        │        \                    │
│        McpClient  McpClient  McpClient              │
│            │          │          │                  │
│     StdioTransport  LegacySse  StreamableHttp       │
│            │          │          │                  │
│        子进程       SSE服务器    HTTP+JWT服务器       │
└─────────────────────────────────────────────────────┘
```

七个包的职责分层：

| 包 | 职责 | 核心类 |
|---|------|-------|
| `mcp/protocol/` | JSON-RPC 2.0 协议实现 | `SimpleJsonParser`, `JsonRpcRequest`, `JsonRpcResponse` |
| `mcp/transport/` | 传输层抽象 | `McpTransport` 接口 + 3 种实现 |
| `mcp/auth/` | OAuth 认证 | `McpAuthConfig`, `JwtTokenProvider` |
| `mcp/client/` | MCP 客户端 | `McpClient`, `McpConnectionManager` |
| `mcp/tool/` | 工具适配层 | `McpToolBridge`, `MappedMcpTool`, `MappedToolRegistry` |
| `mcp/` | 配置与命名 | `McpConfigLoader`, `McpNameUtils` |

#### 传输层——三种实现

`McpTransport` 接口（59 行）定义了传输层的 5 个方法：

```java
public interface McpTransport {
    void start() throws Exception;                          // 建立连接
    JsonRpcResponse sendRequest(JsonRpcRequest req);        // 请求-响应
    void sendNotification(JsonRpcRequest notification);     // 单向通知
    boolean isOpen();                                       // 连接状态
    void close();                                           // 关闭
}
```

**为什么需要三种实现？** MCP 协议演进了三个阶段：

| 传输层 | 协议版本 | 通信模式 | 使用场景 |
|-------|---------|---------|---------|
| `StdioTransport` | 2024-11-05 | 子进程 stdin/stdout | 本地工具（如 amap-proxy.js） |
| `LegacySseTransport` | 2024-11-05 | GET /sse 持久流 + POST /message | 经典 SSE 服务器（如 xt-search） |
| `StreamableHttpTransport` | 2025-03-26 | 独立 POST 一发一收 | MCPHub（如 mt-map + JWT） |

##### StdioTransport（215 行）

子进程传输——通过 `ProcessBuilder` 启动外部进程，用 stdin/stdout 通信：

```java
// 启动子进程
ProcessBuilder pb = new ProcessBuilder(command);
pb.environment().putAll(env);
pb.redirectErrorStream(false);  // stderr 单独消费
process = pb.start();

// 通信方式：换行分隔的 JSON
// 发送 → process.stdin 写入 JSON + "\n"
// 接收 ← process.stdout 逐行读取 JSON
```

**关键设计决策：**

1. **同步 I/O + synchronized 锁**：stdin 写入和 stdout 读取各自持锁，避免多线程交错
2. **守护 stderr 消费线程**：防止 stderr 缓冲区满导致子进程阻塞
3. **优雅关闭三步曲**：`stdin.close()` → `process.destroy()`（SIGTERM）→ `waitFor(500ms)` → `destroyForcibly()`（SIGKILL）

```java
// 优雅关闭的三级降级
private void stopProcess() {
    stdin.close();                           // 1. 通知子进程不再有输入
    process.destroy();                       // 2. SIGTERM
    boolean exited = process.waitFor(GRACEFUL_SHUTDOWN_MS, TimeUnit.MILLISECONDS);
    if (!exited) {
        process.destroyForcibly();           // 3. SIGKILL
    }
}
```

> **学习要点**：`GRACEFUL_SHUTDOWN_MS = 500` 的取值平衡了"给进程足够时间清理"和"不让用户等太久"两个目标。这是系统编程中常见的 graceful shutdown 模式。

##### LegacySseTransport（369 行）

经典 SSE 双通道——这是 MCP 早期最复杂的传输层：

```
┌──────────┐                    ┌──────────┐
│  Client  │ ── GET /sse ────→  │  Server  │  （持久连接，接收 SSE 流）
│          │ ←── event: endpoint │          │  （服务器告知 POST 地址）
│          │ ←── data: /msg?s=x │          │
│          │                    │          │
│          │ ── POST /msg?s=x → │          │  （发送 JSON-RPC 请求）
│          │ ←── data: {resp}   │          │  （通过 SSE 流返回响应）
└──────────┘                    └──────────┘
```

核心数据结构：

```java
// 异步响应匹配：每个请求 ID 对应一个 Future
private final ConcurrentHashMap<String, CompletableFuture<JsonRpcResponse>> 
    pendingRequests = new ConcurrentHashMap<>();
```

工作流程：

1. `start()` → 启动后台线程发送 `GET /sse` 请求，建立持久 SSE 连接
2. SSE 流第一个事件是 `event: endpoint\ndata: /message?sessionId=xxx`
3. `sendRequest()` → 创建 `CompletableFuture`，放入 `pendingRequests[id]`，POST 到 endpoint URL
4. SSE 后台线程持续读取 `data:` 行，解析 JSON-RPC 响应，通过 `id` 匹配 Future 并 complete
5. `sendRequest()` 中 `future.get(RESPONSE_TIMEOUT, SECONDS)` 等待结果

**URL 解析安全检查**（`resolvePostUrl` 静态方法）：

```java
// 安全检查：endpoint 路径不能跳到其他 origin
static String resolvePostUrl(String sseUrl, String endpointPath) {
    URI sseUri = URI.create(sseUrl);
    URI resolved = sseUri.resolve(endpointPath);
    // 确保 resolved 的 scheme + host + port 与原始 SSE URL 一致
    if (!sseUri.getScheme().equals(resolved.getScheme()) ||
        !sseUri.getHost().equals(resolved.getHost())) {
        throw new IllegalArgumentException("Endpoint crosses origin boundary");
    }
    return resolved.toString();
}
```

> **学习要点**：`ConcurrentHashMap<String, CompletableFuture>` 是"一发多收、按 ID 匹配"的标准模式。在网络编程中，凡是请求和响应走不同通道的协议（WebSocket、SSE、消息队列），都需要这种模式。

##### StreamableHttpTransport（232 行）

最简单的传输层——每次请求都是独立的 HTTP POST：

```java
// 每次请求都是独立的 POST
HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create(config.url()))
    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
    .header("Content-Type", "application/json")
    .build();
// 解析响应（支持 JSON 和 SSE 两种格式）
```

三个特色设计：

1. **Session ID 管理**：从 `initialize` 响应提取 `Mcp-Session-Id`，后续请求自动附加
2. **动态 Header 注入**：`Supplier<Map<String,String>> dynamicHeaders`——每次请求时调用，获取最新的 JWT Token
3. **统一 Header 方法**：`applyHeaders()` 按 静态 → 动态 → Session ID 的优先级叠加

```java
// applyHeaders() 三层叠加（消除了之前的代码重复）
private HttpRequest.Builder applyHeaders(HttpRequest.Builder builder) {
    // 1. 静态 headers（来自配置）
    for (var entry : config.headers().entrySet()) {
        builder.header(entry.getKey(), entry.getValue());
    }
    // 2. 动态 headers（来自 JWT Provider）
    if (dynamicHeaders != null) {
        Map<String, String> dynamic = dynamicHeaders.get();  // 每次调用获取最新 Token
        if (dynamic != null) { /* 叠加 */ }
    }
    // 3. Session ID（从 initialize 响应获取）
    if (sessionId != null) {
        builder.header(SESSION_ID_HEADER, sessionId);
    }
    return builder;
}
```

> **学习要点**：`Supplier<Map<String,String>>` 作为动态 Header 源是一个优雅的设计——传输层不需要知道 Token 如何获取，只需要在发请求时"拉取"最新值。这是**依赖反转**的典型应用。

#### McpClient——JSON-RPC 2.0 客户端（425 行）

`McpClient` 是 MCP 协议的客户端实现，封装了完整的握手、工具发现和调用流程。

**协议版本双轨制：**

```java
static final String PROTOCOL_VERSION_2024 = "2024-11-05";  // SSE + stdio
static final String PROTOCOL_VERSION_2025 = "2025-03-26";  // Streamable HTTP (MCPHub)
```

构造时传入版本号，`initialize()` 握手时协商：

```java
// initialize() 握手流程
// 1. 发送 initialize 请求
JsonRpcRequest initReq = new JsonRpcRequest("initialize", Map.of(
    "protocolVersion", protocolVersion,
    "clientInfo", Map.of("name", "claude-code-java", "version", "1.0"),
    "capabilities", Map.of()
));
JsonRpcResponse resp = transport.sendRequest(initReq);

// 2. 解析服务端能力（serverInfo, instructions, capabilities）
// 3. 发送 notifications/initialized 通知（无需响应）
transport.sendNotification(new JsonRpcRequest("notifications/initialized", Map.of()));
```

**工具发现——`listTools()`：**

```java
public List<McpToolInfo> listTools() {
    JsonRpcResponse resp = transport.sendRequest(
        new JsonRpcRequest("tools/list", Map.of()));
    // 解析 result.tools 数组
    // 每个工具提取：name, description, inputSchema.properties, annotations.readOnlyHint
    return tools;
}
```

**工具调用——`callTool()`：**

```java
public String callTool(String toolName, Map<String, Object> arguments) {
    JsonRpcResponse resp = transport.sendRequest(
        new JsonRpcRequest("tools/call", Map.of(
            "name", toolName, "arguments", arguments)));
    // 从 result.content[] 提取 text 类型内容
    // 超长截断：MAX_OUTPUT_CHARS = 100_000
    return text;
}
```

> **学习要点**：`MAX_OUTPUT_CHARS = 100_000` 这个限制很重要——MCP 服务器可能返回海量数据（比如搜索结果），如果不截断会撑爆 LLM 的上下文窗口。

#### McpConnectionManager——多服务器生命周期（387 行）

管理多个 MCP 服务器连接的生命周期，是整个 MCP 子系统的入口类。

**批量连接策略：**

```java
private static final int STDIO_BATCH_SIZE = 3;    // 本地进程批量上限
private static final int REMOTE_BATCH_SIZE = 10;   // 远程服务器批量上限
```

`connectAll()` 流程：

```
1. 分类 → disabled / stdio / remote
2. 禁用的直接标记 DISABLED
3. stdio 类按批次 3 个并发连接（避免 fork bomb）
4. remote 类按批次 10 个并发连接（网络 I/O 为主）
5. 每个连接失败后自动重连（指数退避）
```

**指数退避重连：**

```java
private static final int MAX_RECONNECT_ATTEMPTS = 5;
private static final long INITIAL_BACKOFF_MS = 1000;   // 1s
private static final long MAX_BACKOFF_MS = 30_000;     // 30s

// 退避计算：1s → 2s → 4s → 8s → 16s（不超过 30s）
long backoff = Math.min(INITIAL_BACKOFF_MS * (1L << (attempt - 1)), MAX_BACKOFF_MS);
```

**传输层工厂——`createTransport()`：**

```java
private McpTransport createTransport(McpServerConfig config) {
    return switch (config.transportType()) {
        case STDIO -> new StdioTransport(config.command(), config.args(), config.env());
        case SSE   -> new LegacySseTransport(config.url(), config.headers());
        case HTTP  -> {
            if (config.auth() != null) {
                // 带 JWT 认证的 HTTP 传输
                JwtTokenProvider jwt = new JwtTokenProvider(config.auth());
                yield new StreamableHttpTransport(config, jwt::getAuthHeaders);
            }
            yield new StreamableHttpTransport(config);
        }
    };
}
```

> **学习要点**：`switch` 表达式 + `yield` 是 Java 14+ 的特性。注意 `jwt::getAuthHeaders` 方法引用——它被转换成 `Supplier<Map<String,String>>`，精确匹配 `StreamableHttpTransport` 的 `dynamicHeaders` 参数。这是方法引用和函数式接口的完美结合。

**单次连接流程——`doConnect()`：**

```
createTransport(config)        // 选择传输层
    → transport.start()        // 建立底层连接
    → new McpClient(transport, version)  // 创建客户端（SSE/stdio用2024版本，HTTP用2025版本）
    → client.initialize()      // MCP 握手
    → client.listTools()       // 发现工具
    → client.listResources()   // 发现资源（可选）
    → 保存到 connections Map
```

#### 配置发现——McpConfigLoader（321 行）

两级配置源，项目级覆盖用户级：

```
项目/.mcp.json  >  ~/.claude/settings.json
```

**环境变量展开**——支持 `${VAR}` 和 `${VAR:-default}` 两种语法：

```java
private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

static String expandEnvVars(String value) {
    Matcher m = ENV_VAR_PATTERN.matcher(value);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
        String expr = m.group(1);              // "SOME_KEY" 或 "SOME_KEY:-fallback"
        int defaultIdx = expr.indexOf(":-");
        String varName = defaultIdx >= 0 ? expr.substring(0, defaultIdx) : expr;
        String defaultVal = defaultIdx >= 0 ? expr.substring(defaultIdx + 2) : "";
        String envVal = System.getenv(varName);
        m.appendReplacement(sb, envVal != null ? envVal : defaultVal);
    }
    m.appendTail(sb);
    return sb.toString();
}
```

> **学习要点**：`Matcher.appendReplacement()` + `appendTail()` 是 Java 正则替换的标准模式。注意 `:-` 默认值语法借鉴了 Bash 的 `${VAR:-default}` 约定——保持了与 shell 脚本配置的一致性。

#### 工具命名约定——McpNameUtils（124 行）

MCP 工具需要一个全局唯一的名称，格式为 `mcp__<server>__<tool>`：

```java
public static final String MCP_PREFIX = "mcp__";
public static final String SEPARATOR = "__";

// 标准化：非法字符替换为下划线，最多 64 字符
public static String normalizeForMcp(String name) {
    return name.replaceAll("[^a-zA-Z0-9_-]", "_")
               .substring(0, Math.min(name.length(), 64));
}

// 解析：mcp__xt_search__meituan_search → McpToolRef("xt_search", "meituan_search")
public record McpToolRef(String serverName, String toolName) {}
```

双下划线 `__` 作为分隔符的原因：工具名称中允许单下划线 `_` 和连字符 `-`，只有双下划线不会出现在正常名称中。

#### JWT OAuth 认证——JwtTokenProvider（267 行）

美团地图 MCP（mt-map）需要 OAuth 认证。`JwtTokenProvider` 实现了完整的 JWT 签名和两步 OAuth 流程，**零外部依赖**。

**HS256 JWT 手写实现：**

```java
private String buildJwt(Map<String, Object> claims) {
    // Header: {"alg": "HS256", "typ": "JWT"}
    String header = base64Url("""{"alg":"HS256","typ":"JWT"}""");
    // Payload: claims JSON
    String payload = base64Url(toJson(claims));
    // Signature: HMAC-SHA256
    String signingInput = header + "." + payload;
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
    String signature = base64Url(mac.doFinal(signingInput.getBytes()));
    return signingInput + "." + signature;
}
```

**两步 OAuth 流程：**

```
步骤 1: Client Credentials Grant
   JWT Assertion (iss=clientId, sub=clientId, aud=tokenEndpoint)
     → POST /oauth2/token (grant_type=client_credentials, assertion=jwt)
     → initial access_token

步骤 2: Token Exchange Grant
   initial access_token + JWT Assertion
     → POST /oauth2/token (grant_type=token-exchange, subject_token=initial_token)
     → MCP server scoped access_token（最终使用）
```

**Token 缓存与自动刷新：**

```java
private volatile String cachedToken;
private volatile Instant tokenExpiry;
private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);  // 过期前5分钟刷新

public synchronized String getToken() {
    if (cachedToken != null && Instant.now().isBefore(tokenExpiry.minus(REFRESH_MARGIN))) {
        return cachedToken;  // 缓存命中
    }
    // 两步 OAuth 获取新 Token
    String initial = clientCredentialsGrant(buildJwt(claims));
    cachedToken = tokenExchange(initial, buildJwt(exchangeClaims));
    tokenExpiry = Instant.now().plus(Duration.ofSeconds(expiresIn));
    return cachedToken;
}

// 给 StreamableHttpTransport 的 dynamicHeaders 调用
public Map<String, String> getAuthHeaders() {
    return Map.of("Authorization", "Bearer " + getToken());
}
```

> **学习要点**：`volatile` + `synchronized` 的组合——`volatile` 确保其他线程能看到最新的 Token 值（可见性），`synchronized` 确保只有一个线程执行刷新（互斥性）。`REFRESH_MARGIN = 5 min` 提前刷新避免了 Token 恰好过期时的竞态条件。

#### 工具适配层——直通 vs 映射

MCP 服务器发现的工具需要适配成 Java `Tool` 接口才能被 Agent 使用。项目提供两种适配器：

##### McpToolBridge——直通适配器（186 行）

最简单的适配——MCP 工具名原样暴露，加上 `mcp__<server>__` 前缀：

```java
// 创建直通工具
Tool tool = McpToolBridge.create(serverName, mcpToolInfo, connectionManager);
// 工具名：mcp__amap_maps__amap_weather
// 执行：直接透传参数给 connectionManager.callTool()
```

关键特性：
- `isMcp = true` — 标记为 MCP 工具，默认参与延迟加载
- `MAX_DESCRIPTION_LENGTH = 2048` — 截断超长工具描述，节省 Token
- 从 `annotations.readOnlyHint` 提取只读标志

##### MappedMcpTool——映射适配器（102 行）

带名称映射、参数注入、参数删除的高级适配器。执行时的 6 步流程：

```
用户调用 meituan_search_mix({queries: ["火锅"], location: "望京"})
  │
  ├─ 1. 复制参数（不修改原始 Map）
  ├─ 2. 解析上游名称（literal: "offline_meituan_search_mix"）
  ├─ 3. 删除指定参数（stripParams: ["originalQuery", "type"]）
  ├─ 4. 注入系统参数（injectParams: {lat: 40.007, lng: 116.486, userId: "457809295", ...}）
  ├─ 5. 序列化为 JSON
  └─ 6. 调用 connectionManager.callTool("xt-search", "offline_meituan_search_mix", params)
```

##### ToolMapping——映射规则（101 行）

两种映射模式：

```java
// 字面映射（literal）：固定的名称替换
ToolMapping.literal("meituan_search_mix", "offline_meituan_search_mix",
    List.of("originalQuery", "type"),  // 要删除的参数
    SEARCH_SYSTEM_PARAMS);             // 要注入的参数

// 模板映射（template）：运行时从参数取值
ToolMapping.template("mt_map_direction", "{mode}", "mode");
// 当 mode="driving" 时 → 调用上游工具 "driving"
// 当 mode="walking" 时 → 调用上游工具 "walking"
```

模板映射的精巧之处：`mt_map_direction` 这一个暴露工具，根据 `mode` 参数的值路由到上游的 5 个不同工具（driving/walking/riding/electrobike/transit）。

##### MappedToolRegistry——12 个映射工具定义（267 行）

工厂类，集中定义所有映射工具：

| 暴露名称 | 上游工具 | 服务器 | 映射模式 |
|---------|---------|-------|---------|
| `meituan_search_mix` | `offline_meituan_search_mix` | xt-search | literal + 注入 6 参数 |
| `content_search` | `content_search_plus_v2` | xt-search | literal |
| `id_detail_pro` | `id_detail_pro` | xt-search | literal（名称一致） |
| `meituan_search_poi` | `offline_meituan_search_poi` | xt-search | literal + 注入 5 参数 |
| `mt_map_geo` | `geo` | mt-map | literal |
| `mt_map_regeo` | `regeo` | mt-map | literal |
| `mt_map_text_search` | `text` | mt-map | literal |
| `mt_map_nearby` | `nearby` | mt-map | literal |
| `mt_map_direction` | `{mode}` | mt-map | **template** |
| `mt_map_distance` | `{mode}distancematrix` | mt-map | **template** |
| `mt_map_iplocate` | `iplocate` | mt-map | literal |
| `mt_map_poiprovide` | `poiprovide` | mt-map | literal |

系统参数注入示例（`SEARCH_SYSTEM_PARAMS`）：

```java
private static final Map<String, Object> SEARCH_SYSTEM_PARAMS = Map.of(
    "lat", 40.007936,        // 用户纬度（默认望京）
    "lng", 116.486665,       // 用户经度
    "userId", "457809295",   // 美团用户 ID
    "uuid", "0000000...357", // 设备唯一标识
    "searchSource", 15,      // 搜索来源标识
    "mode", "think"          // 搜索模式
);
```

> **学习要点**：映射层的价值在于**关注点分离**。LLM 看到的是简洁、友好的 `meituan_search_mix`；底层调用的是带完整参数的 `offline_meituan_search_mix`。系统参数（lat/lng/userId）对 LLM 透明——它不需要知道这些技术细节。

#### MCP 子系统的连接全景

把所有组件串起来：

```
.mcp.json / settings.json
    │
    ▼
McpConfigLoader.loadAll()           ← 配置发现 + 环境变量展开
    │
    ▼
McpConnectionManager.connectAll()   ← 批量并发连接 + 指数退避重连
    │
    ├─ StdioTransport + McpClient   → amap-maps 高德地图
    ├─ LegacySseTransport + McpClient → xt-search 美团搜索
    └─ StreamableHttpTransport + JwtTokenProvider + McpClient → mt-map 美团地图
    │
    ▼
McpToolBridge.createAll()           ← 直通工具（mcp__server__tool 格式）
MappedToolRegistry.createAllTools() ← 映射工具（友好名称 + 参数注入）
    │
    ▼
ToolRegistry                        ← 内置工具 + MCP 直通 + MCP 映射，统一注册
    │
    ▼
AgentEngine → ToolOrchestrator      ← 模型调用任何工具，走统一路径
```

**关键设计决策总结：**

1. **零外部依赖**：JSON 解析（`SimpleJsonParser`）、JWT 签名（`javax.crypto.Mac`）、SSE 解析（`BufferedReader` 逐行）全部手写
2. **传输层多态**：同一个 `McpClient` 可以工作在三种不同传输层上，客户端代码无感知
3. **配置驱动**：新增 MCP 服务器只需修改 `.mcp.json`，无需改代码
4. **优雅降级**：连接失败不阻塞整个系统，标记 FAILED 后其他服务器继续工作

### 6.7 流式工具执行（tool/streaming/）

传统模式下，Agent Loop 必须等待模型回复**完整结束**后才能开始执行工具。流式工具执行打破了这个限制——当 SSE 流中一个 `tool_use` block 完成时，立即开始执行，不等待后续 block。

#### 为什么需要流式工具执行？

```
传统模式：
  SSE流开始 ─── tool_use_1 完成 ─── tool_use_2 完成 ─── SSE流结束 ─── 执行tool_1 ─── 执行tool_2
                                                                      │←── 浪费的等待时间 ──→│

流式模式：
  SSE流开始 ─── tool_use_1 完成 ─── tool_use_2 完成 ─── SSE流结束
                    │ 立即执行tool_1    │ 立即执行tool_2
                    └───────────────── 并行 ─────────────┘
```

当模型输出多个工具调用时（比如同时调 `read_file` 和 `list_files`），流式模式可以显著减少总延迟。

#### 三个核心类

##### StreamingToolConfig——特性开关

```java
// 环境变量控制：ENABLE_STREAMING_TOOL_EXECUTION
public enum Mode { ENABLED, DISABLED, AUTO }

// AUTO 模式下的启用条件
public static boolean isEnabled(StreamCallback callback, int toolCount) {
    return switch (mode) {
        case ENABLED -> true;
        case DISABLED -> false;
        case AUTO -> callback != null && toolCount >= AUTO_THRESHOLD;
    };
}
```

**默认 DISABLED**——这是一个性能优化特性，需要显式启用。`AUTO` 模式下，当注册工具数量 ≥ 阈值时自动启用。

##### StreamingToolCallback——回调桥接

扩展了 `StreamCallback`（文本 Token 回调），添加工具完成通知：

```java
public interface StreamingToolCallback extends StreamCallback {
    // 继承自 StreamCallback
    void onTextToken(String token);

    // 新增：SSE 流中一个 tool_use block 完成时调用
    void onToolUseComplete(ToolCallBlock toolCall);
}
```

在 `AnthropicProviderClient.parseSseStream()` 中，当检测到 `content_block_stop` 且该 block 是 `tool_use` 类型时，触发 `onToolUseComplete()`。

##### StreamingToolExecutor——状态机 + 调度器（核心）

每个工具调用是一个 `TrackedTool`，按状态机推进：

```
QUEUED → EXECUTING → COMPLETED
                  └→ FAILED
```

**并发调度模型：**

```java
// concurrent-safe 工具：并行执行
// 非 concurrent-safe 工具：独占执行（等其他都完成后才开始）

private final ReentrantLock schedulingLock = new ReentrantLock();

void addTool(ToolCallBlock toolCall) {
    TrackedTool tracked = new TrackedTool(toolCall, status=QUEUED);
    tools.add(tracked);
    scheduleExecution();   // 尝试调度
}

private void scheduleExecution() {
    schedulingLock.lock();
    try {
        for (TrackedTool t : tools) {
            if (t.status != QUEUED) continue;
            if (isConcurrencySafe(t)) {
                // 并发安全 → 直接提交线程池
                t.status = EXECUTING;
                executor.submit(() -> executeTool(t));
            } else {
                // 非并发安全 → 等所有其他工具完成
                if (allOthersCompleted(t)) {
                    t.status = EXECUTING;
                    executor.submit(() -> executeTool(t));
                }
                // else: 暂不调度，等其他工具完成后的 finally 块中重新触发
            }
        }
    } finally {
        schedulingLock.unlock();
    }
}
```

**结果收集**——保持插入顺序：

```java
// 每个 TrackedTool 持有一个 CompletableFuture
List<ConversationMessage> awaitAllResults() {
    List<ConversationMessage> results = new ArrayList<>();
    for (TrackedTool t : tools) {         // 按添加顺序遍历
        results.add(t.future.join());     // 阻塞等待每个结果
    }
    return results;
}
```

> **学习要点**：`schedulingLock` 的作用不是保护数据，而是保护**调度决策的原子性**。如果两个线程同时调用 `scheduleExecution()`（一个是 SSE 读取线程通过 `addTool`，另一个是工具完成后的 `finally` 回调），没有锁可能导致同一个工具被重复提交。

#### 与 AgentEngine 的集成

```java
// AgentEngine.executeLoop() 中的分支
if (streamingEnabled) {
    assistantMessage = callModelWithStreamingTools(eventSink);
    // ↓ 流式路径：工具已在 SSE 流中开始执行
    toolResults = streamingToolExecutor.awaitAllResults();
} else {
    assistantMessage = modelAdapter.nextReply(memory.snapshot(), context);
    // ↓ 经典路径：SSE 结束后批量执行
    toolResults = toolOrchestrator.execute(toolCalls, context, eventSink);
}
```

**向后兼容设计**：
- `AgentEngine` 的 9 参数构造器是新增的，旧的 5 参数和 7 参数构造器保持不变
- `ModelAdapter` 接口新增 `default` 方法 `nextReply(conversation, context, callback)`，不破坏已有实现
- 特性开关默认 DISABLED，不影响现有行为

### 6.8 工具延迟加载（Tool Deferred Loading）

当注册的工具数量很多时（本项目 20+ 个工具），将所有工具 schema 发送给 API 会消耗大量 Token。延迟加载机制让模型先通过 `ToolSearch` 工具发现需要的工具，再将其 schema 加入 API 请求。

#### 判定规则——哪些工具被延迟？

```java
// ToolSearchUtils.isDeferredTool() 的判定优先级
public static boolean isDeferredTool(Tool tool) {
    if (meta.alwaysLoad())  return false;    // 1. alwaysLoad=true → 永不延迟
    if (meta.isMcp())       return true;     // 2. MCP 工具 → 默认延迟
    if (isToolSearch(tool))  return false;   // 3. ToolSearch 自身 → 永不延迟
    if (meta.shouldDefer()) return true;     // 4. 显式标记 → 延迟
    return false;                            // 5. 其他 → 不延迟
}
```

核心逻辑：**内置工具默认全量发送，MCP 工具默认延迟加载**。因为 MCP 工具数量不可控（外部服务器可能注册几十上百个工具）。

#### 三种工作模式

通过环境变量 `TOOL_SEARCH_MODE` 控制：

| 模式 | 行为 | 适用场景 |
|-----|------|---------|
| `STANDARD`（默认） | 所有工具 schema 全量发送 | 工具少，无需节省 Token |
| `TST` | 延迟工具一律不发 schema | 强制延迟加载 |
| `TST_AUTO` | 延迟工具 Token 总量超过阈值时才启用 | 自适应 |

`TST_AUTO` 的阈值计算：

```java
private static final double AUTO_THRESHOLD_PERCENTAGE = 0.10;  // 10%
private static final int DEFAULT_CONTEXT_WINDOW = 200_000;

// 当延迟工具的 schema 字符数 > 上下文窗口 × 10% × 2.5（chars/token 比率）时启用
boolean shouldDefer = totalDeferredChars > DEFAULT_CONTEXT_WINDOW * AUTO_THRESHOLD_PERCENTAGE * CHARS_PER_TOKEN;
```

#### 工具发现流程

```
1. 模型想调用 meituan_search_mix（但 schema 未发送）
     ↓
2. ToolOrchestrator 检测到 schema 未发送
     → 注入提示："此工具的 schema 未在本次请求中发送，
        请先使用 ToolSearch 发现该工具"
     ↓
3. 模型调用 ToolSearch({query: "美团搜索"}) 或 ToolSearch({select: "meituan_search_mix"})
     ↓
4. ToolSearchTool 返回匹配的工具列表 + ToolReferenceBlock
     ↓
5. LlmConversationMapper.filterToolsForApi() 扫描会话中的 ToolReferenceBlock
     → 提取已发现的工具名 → 将这些工具的 schema 加入 API 请求
     ↓
6. 下一轮模型调用时，meituan_search_mix 的 schema 已可用
```

`ToolReferenceBlock` 是发现协议的载体：

```java
// ToolSearchTool 返回结果中嵌入的标记
public record ToolReferenceBlock(String toolName) implements ContentBlock {
    // 序列化为：[tool_reference] meituan_search_mix
}
```

`LlmConversationMapper` 在构建 API 请求时扫描这些标记：

```java
// filterToolsForApi() 逻辑
Set<String> discoveredNames = extractDiscoveredToolNames(conversation);  // 扫描 ToolReferenceBlock
return allTools.stream()
    .filter(t -> !isDeferredTool(t) || discoveredNames.contains(t.metadata().name()))
    .toList();
```

> **学习要点**：延迟加载的巧妙之处在于它利用了 Agent Loop 自身的迭代特性——"发现工具"和"使用工具"在不同的循环轮次完成。第一轮发现，第二轮使用。`deferLoading` flag 还会告诉模型"有些工具存在但 schema 未发送"，引导模型主动调用 `ToolSearch`。

### 6.9 Skill 系统（skill/）

Skill 是用户可配置的"技能"——用 Markdown 文件定义一段提示词模板，Agent 可以通过工具调用来执行。

#### Skill 文件格式

```markdown
---
name: commit
description: Create a git commit with a descriptive message
allowed_tools:
  - Bash
  - read_file
context: inline
arguments:
  - name: message
    description: Optional commit message override
---

Analyze the current git diff and create a well-formatted commit.
$ARGUMENTS
```

文件存放位置（项目级覆盖用户级）：

```
~/.claude/skills/*.md                    ← 用户级
~/.claude/skills/<name>/SKILL.md         ← 用户级（目录模式）
<project>/.claude/skills/*.md            ← 项目级
<project>/.claude/skills/<name>/SKILL.md ← 项目级（目录模式）
```

#### 核心类

**SkillDefinition**——不可变记录：

```java
public record SkillDefinition(
    String name,              // 技能名
    String description,       // 描述
    String whenToUse,         // 何时使用提示
    List<String> allowedTools,// 允许的工具列表
    String model,             // 指定模型（可选）
    Context context,          // INLINE 或 FORK
    String promptTemplate,    // 提示词模板（含 $ARGUMENTS 占位符）
    Path sourceFile,          // 源文件路径
    Source source             // USER 或 PROJECT
) {
    public enum Context { INLINE, FORK }
    public enum Source { USER, PROJECT }
}
```

**SkillLoader**——零依赖 YAML 解析器：

```java
// 手写 YAML frontmatter 解析（支持 key-value、引号字符串、列表、嵌套对象）
// 两种扫描布局：
// 1. 平面模式：skills/*.md → name 取自文件名（去掉 .md）
// 2. 目录模式：skills/<name>/SKILL.md → name 取自目录名
```

**SkillTool**——`Tool` 实现：

```java
// 模型调用：Skill({skill: "commit", args: "-m 'Fix bug'"})
public ToolResult execute(Map<String, Object> input, ToolExecutionContext ctx) {
    String skillName = (String) input.get("skill");
    String args = (String) input.get("args");
    SkillDefinition skill = registry.findByName(skillName);
    String prompt = skill.promptTemplate().replace("$ARGUMENTS", args);

    if (skill.context() == Context.INLINE) {
        // 内联模式：返回提示词，模型在当前会话中处理
        return ToolResult.text("<skill>" + prompt + "</skill>");
    } else {
        // Fork 模式：创建子 Agent 执行
        return subAgentRunner.runSync(agentDef, prompt, ...);
    }
}
```

两种执行模式的区别：

| 模式 | 执行方式 | 上下文 | 适用场景 |
|-----|---------|-------|---------|
| **inline**（默认） | 提示词作为工具结果返回 | 共享当前会话 | 简单指令、需要上下文的操作 |
| **fork** | 创建独立子 Agent | 独立 Memory | 复杂任务、避免污染主会话 |

### 6.10 系统提示词构建（prompt/）

系统提示词决定了 Agent 的"人格"和行为规范。`SystemPromptBuilder` 采用模块化组装模式。

#### 静态段（编译时确定）

```java
// SystemPromptSections.java — 全中文，面向美团Agent「小团」
ROLE_AND_GUIDELINES  // 角色定义 + 工具使用原则 + 搜索完整性三原则
SYSTEM               // 系统行为规范（Markdown、并行调用、注入防护）
OUTPUT_STYLE         // 输出风格（简洁有结构、列表/表格）
DEFAULT_AGENT_PROMPT // 子代理兜底提示
AGENT_NOTES          // 子代理行为规范
TOOL_RESULT_SUMMARY  // 工具结果引用提醒
```

#### 动态段（运行时生成）

```java
// SystemPromptBuilder.java 中的动态段生成方法
availableToolsSection(enabledToolNames)  // 可用工具列表（中文描述）
skillListingSection(skillRegistry)       // Skill 列表（从 SkillRegistry 构建）
EnvironmentInfo.collect(cwd, modelName)  // 环境信息（CWD、git、平台、Java版本）
languageSection(language)                // 语言指令（"请始终使用{language}回复"）
memorySection(workspaceRoot)             // 加载项目 CLAUDE.md
mcpInstructionsSection(mcpManager)       // MCP 服务器提供的指令
```

#### 三种组装方式

```java
// 1. 交互 REPL 模式（最完整）
buildMainPrompt() = ROLE_AND_GUIDELINES + SYSTEM + availableTools + skillListing
                  + OUTPUT_STYLE + env + language + memory + mcp + toolResultSummary

// 2. 单任务模式（精简）
buildDemoPrompt() = ROLE_AND_GUIDELINES + OUTPUT_STYLE + env + memory

// 3. 子 Agent 模式（使用 Agent 自身的提示词）
buildAgentPrompt() = agentDefinition.prompt + AGENT_NOTES + env + memory
```

> **学习要点**：模块化提示词的价值在于**同一套内容可以按场景自由组合**。比如子 Agent 不需要 `availableToolsSection`（它有自己的工具过滤），也不需要 `OUTPUT_STYLE`（由 Agent 定义控制）。

### 6.11 任务管理系统（task/）

为 Agent 提供跨轮次的任务跟踪能力。

```java
// TaskStore — 线程安全的内存任务仓库
public class TaskStore {
    private final ConcurrentHashMap<String, Task> tasks = new ConcurrentHashMap<>();
    private final AtomicInteger idGenerator = new AtomicInteger(1);

    public Task create(String subject, String description) {
        String id = String.valueOf(idGenerator.getAndIncrement());
        Task task = new Task(id, subject, description, Status.PENDING, ...);
        tasks.put(id, task);
        return task;
    }
}
```

`Task` 是不可变 record，状态变更通过 wither 方法生成新实例：

```java
public record Task(String id, String subject, String description,
                   Status status, String owner, ...) {
    public Task withStatus(Status newStatus) {
        return new Task(id, subject, description, newStatus, owner, ...);
    }
}
```

4 个任务工具：

| 工具 | 功能 | 典型调用 |
|-----|------|---------|
| `task_create` | 创建任务 | `{subject: "调研竞品", description: "..."}` |
| `task_get` | 查询单个任务 | `{taskId: "3"}` |
| `task_list` | 列出所有任务 | `{}` |
| `task_update` | 更新状态/删除 | `{taskId: "3", status: "completed"}` |

> **学习要点**：`Task` 使用 record + wither 模式而非 setter，确保了不可变性。`ConcurrentHashMap` + `AtomicInteger` 的组合提供了无锁的并发安全——在多 Agent 并发操作任务时不需要额外同步。

---

## 7. 关键设计模式与 Java 惯用法

本项目大量运用了现代 Java（17+）特性和经典设计模式。以下列出最值得学习的 patterns，每个都标注了在项目中的实际使用位置。

### 7.1 sealed interface + record 实现代数数据类型

```java
// message/ContentBlock.java — 密封接口限制实现类
public sealed interface ContentBlock
    permits TextBlock, ToolCallBlock, ToolResultBlock, SummaryBlock, ToolReferenceBlock {}

// 每个变体是 record（自动 equals/hashCode/toString）
public record TextBlock(String text) implements ContentBlock {}
public record ToolCallBlock(String id, String name, Map<String,Object> input) implements ContentBlock {}
```

**使用位置**：`message/` 包全部 5 种 ContentBlock。

**学习价值**：sealed interface 让编译器在 `switch` 表达式中检查是否覆盖了所有变体（pattern matching exhaustiveness），是 Java 版本的"Sum Type"。

### 7.2 record + wither 实现不可变状态

```java
// task/Task.java
public record Task(String id, String subject, Status status, ...) {
    public Task withStatus(Status s) { return new Task(id, subject, s, ...); }
    public Task withOwner(String o)  { return new Task(id, subject, status, o, ...); }
}
```

**使用位置**：`Task`、`CompactResult`、`ToolMapping`、`SkillDefinition`。

**学习价值**：wither 模式让 record 既保持不可变性，又支持"部分更新"。在并发场景下，不可变对象天然线程安全。

### 7.3 策略模式——Tool 接口

```java
// tool/Tool.java
public interface Tool {
    ToolMetadata metadata();
    ToolResult execute(Map<String, Object> input, ToolExecutionContext context);
}
```

**使用位置**：11 个内置工具 + 动态 MCP 工具 + 映射工具，全部实现此接口。

**学习价值**：`ToolOrchestrator` 和 `AgentEngine` 通过接口操作工具，完全不知道具体实现。新增工具只需实现接口 + 注册到 `ToolRegistry`。

### 7.4 模板方法模式——三级压缩

```java
// 三个压缩器共享相同的调用契约
interface Compactor {
    CompactResult compact(List<ConversationMessage> messages, ...);
}
// ConversationMemory 按优先级依次尝试
```

**使用位置**：`MicroCompactor` → `SessionMemoryCompactor` → `FullCompactor`。

### 7.5 工厂方法 + switch 表达式

```java
// McpConnectionManager.createTransport()
return switch (config.transportType()) {
    case STDIO -> new StdioTransport(...);
    case SSE   -> new LegacySseTransport(...);
    case HTTP  -> { /* JWT 注入逻辑 */ yield new StreamableHttpTransport(...); }
};
```

**使用位置**：`McpConnectionManager`、`ModelAdapterFactory`、`BuiltInAgents`。

### 7.6 函数式接口 + 方法引用

```java
// StreamCallback 是 @FunctionalInterface
StreamCallback callback = System.out::print;  // 方法引用

// JwtTokenProvider 作为 Supplier<Map<String,String>>
new StreamableHttpTransport(config, jwt::getAuthHeaders);

// Consumer<String> 作为事件接收器
engine.run("task", eventSink::accept);
```

**使用位置**：贯穿整个项目——`Consumer<String>`（事件输出）、`Supplier<Map>`（动态 Header）、`StreamCallback`（SSE Token）。

### 7.7 ConcurrentHashMap + CompletableFuture 异步匹配

```java
// LegacySseTransport — 按 ID 匹配请求和响应
ConcurrentHashMap<String, CompletableFuture<JsonRpcResponse>> pendingRequests;

// 发送：创建 Future → 放入 Map → POST 请求
CompletableFuture<JsonRpcResponse> future = new CompletableFuture<>();
pendingRequests.put(request.id(), future);
httpClient.send(postRequest, ...);
return future.get(TIMEOUT, SECONDS);

// 接收（SSE 线程）：解析 JSON → 按 ID 取 Future → complete
pendingRequests.remove(id).complete(response);
```

**使用位置**：`LegacySseTransport`（请求-响应匹配）、`SubAgentRunner`（异步 Agent 结果）。

### 7.8 零依赖哲学

整个项目只使用 JDK 标准库，所有通常需要第三方库的功能都手写实现：

| 通常用的库 | 本项目的替代 | 位置 |
|----------|------------|------|
| Jackson/Gson | `SimpleJsonParser` + 手写 JSON 构建 | `mcp/protocol/`, `model/llm/` |
| Nimbus JOSE JWT | `javax.crypto.Mac` + 手动 Base64 | `mcp/auth/JwtTokenProvider` |
| OkHttp/Apache HTTP | `java.net.http.HttpClient` | 所有 HTTP 通信 |
| SnakeYAML | 手写 frontmatter 解析器 | `skill/SkillLoader` |
| SLF4J/Logback | `System.err.println` + `Consumer<String>` | 全局 |

> **学习价值**：零依赖不是为了"炫技"，而是为了**最小化部署 footprint**。一个 CLI 工具如果拉进 30MB 的 Jackson + Netty，启动时间会从 0.5s 膨胀到 3s+。对于 Agent 这种需要快速启动的工具，这很重要。

---

## 8. 测试架构与学习方法

### 8.1 测试金字塔

```
                    ┌──────────┐
                    │  集成测试  │  ← SkillIntegrationTest, StreamingToolIntegrationTest
                   ┌┴──────────┴┐
                   │   组件测试   │  ← AgentEngineTest, McpConnectionManagerTest
                  ┌┴────────────┴┐
                  │    单元测试    │  ← ToolSearchUtilsTest, TaskStoreTest, TokenEstimatorTest
                  └──────────────┘
                     723 个测试
```

### 8.2 Mock 策略

项目**不使用任何 Mock 框架**（Mockito 等），全部使用手写 Mock：

```java
// test/.../mock/MockModelAdapter.java — 预编程的回复序列
class MockModelAdapter implements ModelAdapter {
    private final Queue<ConversationMessage> replies = new LinkedList<>();

    MockModelAdapter(ConversationMessage... replies) {
        this.replies.addAll(List.of(replies));
    }

    @Override
    public ConversationMessage nextReply(...) {
        return replies.poll();  // 按顺序弹出预编程的回复
    }
}
```

**常见 Mock 类型**：
- `MockModelAdapter` — 预编程回复序列（用于 Agent Loop 测试）
- `MockTransport` — 预编程 JSON-RPC 响应（用于 MCP 测试）
- 内联 lambda — 单方法接口直接用 lambda mock

### 8.3 推荐的学习路径——通过测试理解代码

| 想理解的主题 | 建议阅读的测试 |
|------------|--------------|
| Agent Loop 闭环 | `AgentEngineTest` — 看 Mock 回复如何驱动多轮循环 |
| 三级压缩 | `ConversationMemoryTest` — 看何时触发、压缩结果 |
| 工具配对保护 | `LlmConversationMapperTest` — 看孤立 tool_result 如何被过滤 |
| MCP 传输层 | `LegacySseTransportTest` — 看 SSE 事件如何被解析和分发 |
| 工具延迟加载 | `ToolSearchUtilsTest` — 看 isDeferredTool 的判定边界 |
| 流式工具执行 | `StreamingToolExecutorTest` — 看并发调度和状态机 |
| Skill 加载 | `SkillLoaderTest` — 看 YAML 解析和目录扫描 |
| 子 Agent 通信 | `AgentTaskTest` — 看消息队列的入队和消费 |

> **学习方法**：先读测试（理解"做什么"），再读实现（理解"怎么做"）。测试是最好的文档——它们展示了每个组件的**预期行为**和**边界条件**。

---

## 9. 与 TypeScript 原版的对应关系

本项目是 Claude Code TypeScript 原版的 Java 重新实现。以下是核心概念的对应表：

| TypeScript 原版 | Java 实现 | 关键差异 |
|----------------|----------|---------|
| `agent.ts` agentic loop | `AgentEngine.executeLoop()` | TS 用 async/await，Java 用同步循环 |
| `autoCompact.ts` | `ConversationMemory` 三级压缩 | 逻辑等价，Java 用显式 if-else 替代 TS 的链式 async |
| `toolSearch.ts` | `ToolSearchUtils` + `ToolSearchTool` | 判定逻辑 1:1 对应 |
| `claude.ts` queryModel | `LlmConversationMapper.toRequest()` | 双集合 filteredTools/allTools 设计一致 |
| `SubAgent` process isolation | `SubAgentRunner` thread isolation | 最大差异：TS 用子进程，Java 用线程 |
| `MDK` session memory template | `SessionMemory` 10-section Markdown | 内容结构一致 |
| SSE `EventSource` API | `LegacySseTransport` 手写解析 | Java 无 EventSource，全部手写 |
| `process.spawn()` | `ProcessBuilder.start()` | 进程管理 API 不同，语义相同 |
| npm/npx 工具发现 | 无 | Java 生态没有等价的包管理器集成 |

**架构级别的差异：**

1. **并发模型**：TS 用 `Promise`/`async-await` 单线程事件循环；Java 用 `ExecutorService`/`CompletableFuture` 多线程
2. **进程 vs 线程**：TS 的子 Agent 是真实子进程（内存隔离）；Java 用线程 + 独立 `ConversationMemory`（逻辑隔离）
3. **JSON 处理**：TS 内置 `JSON.parse/stringify`；Java 手写 `SimpleJsonParser` + 字符串拼接
4. **类型系统**：TS 的 union type 变成 Java 的 sealed interface；TS 的 interface 变成 Java 的 record

---

## 10. 进阶学习建议

### 10.1 代码阅读路线图

**第一天：理解核心循环**
1. `AgentEngine.executeLoop()` — 整个系统的心跳
2. `ConversationMessage` + `ContentBlock` — 消息协议
3. `ToolOrchestrator.execute()` — 工具执行策略

**第二天：理解模型对接**
1. `LlmConversationMapper.toRequest()` — 内部消息 → API 格式
2. `AnthropicProviderClient.parseSseStream()` — SSE 流解析
3. `ModelAdapter` 接口 — 模型层抽象

**第三天：理解上下文管理**
1. `ConversationMemory` 三级压缩 — Token 预算管理
2. `TokenEstimator` — 字符级 Token 估算
3. `SessionMemory` — 10 段式结构化记忆

**第四天：理解扩展机制**
1. `mcp/` 包 — 外部工具集成
2. `skill/` 包 — 用户自定义技能
3. `tool/streaming/` 包 — 性能优化

### 10.2 实践练习

1. **添加一个新工具**：实现 `Tool` 接口，注册到 `ToolRegistry`，观察 Agent 如何自动使用它
2. **添加一个新 MCP 服务器**：在 `.mcp.json` 中配置，观察 `McpConnectionManager` 的自动发现
3. **编写一个 Skill**：创建 `.claude/skills/my-skill.md`，通过 `/my-skill` 或 `Skill` 工具调用
4. **调试 Token 预算**：修改 `AUTO_COMPACT_BUFFER` 观察压缩行为变化
5. **启用流式工具执行**：设置 `ENABLE_STREAMING_TOOL_EXECUTION=ENABLED`，对比性能差异

### 10.3 扩展方向

- **持久化层**：当前所有状态在内存中，可以添加文件/数据库持久化
- **插件系统**：将 MCP + Skill 统一为插件架构
- **多模型支持**：`ModelAdapter` 已经抽象好了，可以添加 OpenAI、Gemini 等 provider
- **Web UI**：当前只有 CLI，可以添加 WebSocket 接口给前端
- **指标收集**：在关键路径埋点，收集工具执行耗时、Token 消耗、压缩效果等

### 10.4 推荐阅读

- [Anthropic Messages API 文档](https://docs.anthropic.com/en/api/messages) — 理解 tool_use/tool_result 协议
- [MCP 协议规范](https://modelcontextprotocol.io/) — 理解 JSON-RPC 2.0 + SSE/HTTP 传输
- [Claude Code 开源仓库](https://github.com/anthropics/claude-code) — 对比 TypeScript 原版实现
- Java 17 语言特性（sealed class、record、pattern matching、switch expression）
