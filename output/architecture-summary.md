# Claude Code Java — 架构摘要

> 一个用 Java 重建 Claude Code 核心设计思想的可运行 demo。  
> 技术栈：Java 21 + Maven，无 Spring，无外部框架。

---

## 一、整体架构鸟瞰

```
┌─────────────────────────────────────────────────────────────┐
│                      DemoApplication                        │
│        （入口：组装所有组件，传入 goal，启动 AgentEngine）         │
└───────────────────────────┬─────────────────────────────────┘
                            │
            ┌───────────────▼───────────────┐
            │          AgentEngine          │  ← 核心主循环
            │    (memory, model, tools)     │
            └───┬───────────────────────┬───┘
                │                       │
    ┌───────────▼──────┐    ┌───────────▼────────────┐
    │  ConversationMemory│   │    ToolOrchestrator     │
    │  (上下文 + 压缩)   │   │  (并发安全执行 + 权限)   │
    └───────────────────┘   └────────────────────────┘
                │                       │
    ┌───────────▼──────┐    ┌───────────▼────────────┐
    │   ModelAdapter    │   │      ToolRegistry       │
    │  (LLM / Rules)    │   │  list/read/write tools  │
    └───────────────────┘   └────────────────────────┘
```

---

## 二、核心模块详解

### 1. AgentEngine — 消息驱动的主循环

**文件：** `agent/AgentEngine.java`

```
USER goal
  → memory.append(user)
  → loop (maxTurns=12):
      modelAdapter.nextReply(snapshot) → assistantMessage
      memory.append(assistant)
      if toolCalls.isEmpty() → return (终止)
      toolOrchestrator.execute(toolCalls) → toolResults
      memory.append(toolResults)
  → fallback（超出轮数）
```

**关键设计决策：**
- 主循环显式展开，不隐藏在框架内——让"消息→工具→消息"的闭环一目了然
- 支持两种模式：`run()`（单次任务）和 `chat()`（多轮交互，memory 共享）
- 超出 maxTurns 返回兜底消息，而不是抛异常（适合交互式场景）

---

### 2. ConversationMemory — 上下文管理与压缩

**文件：** `agent/ConversationMemory.java`

**三段结构：**
```
[ system header ][ compacted summary ][ recent tail (preservedTailSize=12) ]
```

**压缩触发逻辑：**
- 可变消息数超过 `maxMutableMessagesBeforeCompact=24` 时触发
- 压缩时保留最近 12 条消息（tail），旧消息送入 `SimpleContextCompactor`
- **关键修正**：`adjustTailForToolPairing()` 确保 `tool_use / tool_result` 配对不被截断（否则 Anthropic API 返回 400）

**SimpleContextCompactor：**
- 不调用 LLM，纯本地规则压缩
- 保留最多 10 条旧消息的摘要行（每行 ≤140 字符），超出部分折叠提示

---

### 3. ContentBlock — 统一消息流

**文件：** `message/ContentBlock.java`（sealed interface）

```java
ContentBlock
  ├── TextBlock       // 普通文本
  ├── ToolCallBlock   // 模型发出的工具调用
  ├── ToolResultBlock // 工具执行结果（以 USER 角色回传）
  └── SummaryBlock    // 压缩摘要块
```

**设计意图：** 文本、工具调用、工具结果全部走同一条消息链，避免状态散落在多个对象。`role` 保留在 `ConversationMessage` 中，用于 compact、规则模型和审计。

---

### 4. ModelAdapter — 思考与执行分离

**文件：** `model/ModelAdapter.java`（接口）

| 实现 | 说明 |
|---|---|
| `LlmBackedModelAdapter` | 真实 LLM（OpenAI / Anthropic），通过 `ModelAdapterFactory` 创建 |
| `RuleBasedModelAdapter` | 无网络依赖的规则模型，固定执行 list→read→write 三步，用于验证 agent loop 工程边界 |

**LLM 路由：**
```
ModelAdapterFactory.create(config)
  OPENAI    → OpenAiProviderClient
  ANTHROPIC → AnthropicProviderClient
  RULES     → RuleBasedModelAdapter（不发网络请求）
```

`AbstractLlmProviderClient` 提供骨架默认实现（不覆写 `generate()` 则返回提示信息），方便开发阶段验证循环。

---

### 5. ToolOrchestrator — 并发安全的工具执行

**文件：** `tool/ToolOrchestrator.java`

**分批执行策略（不是简单全并发）：**
```
calls → partition() → List<Batch>
  Batch(concurrentSafe=true)  → executeConcurrent (线程池, maxConcurrency=4)
  Batch(concurrentSafe=false) → 顺序执行
```

- `concurrencySafe` 由 `ToolMetadata` 声明（`list_files` / `read_file` = true，`write_file` = false）
- 连续的安全 batch 合并；非安全工具单独串行

**执行链：**
```
executeSingle(call):
  1. toolRegistry.require(toolName)    // 查找工具
  2. tool.validate(input)              // 参数校验
  3. permissionPolicy.evaluate(...)    // 权限检查
  4. tool.execute(input, context)      // 实际执行
  → ConversationMessage(USER, ToolResultBlock)
```

---

### 6. PermissionPolicy — 工具治理层

**文件：** `tool/WorkspacePermissionPolicy.java`

**三条核心规则：**
1. `PathDomain.NONE`：无路径约束，直接放行
2. **路径越界检查**：`resolved.startsWith(root)` 防止目录穿越
3. **写操作隔离**：`destructive=true` 的工具只允许写入 `artifactRoot`，不能污染被分析的工作区

---

### 7. Tool 实现清单

| 工具 | readOnly | concurrencySafe | destructive | PathDomain | 说明 |
|---|---|---|---|---|---|
| `list_files` | ✓ | ✓ | ✗ | WORKSPACE | 递归列目录，depth 1-6 |
| `read_file` | ✓ | ✓ | ✗ | WORKSPACE | 读文件，超 6000 字符截断（在生产端控制上下文） |
| `write_file` | ✗ | ✗ | ✓ | ARTIFACT | 写入输出目录，产物沉淀 |

---

## 三、数据流全链路

```
1. DemoApplication 组装所有组件
   workspaceRoot (分析目标) + artifactRoot (输出目录)

2. AgentEngine.run(goal)
   → memory 追加 user 消息
   → 进入 executeLoop

3. 每轮：
   a. modelAdapter.nextReply(memory.snapshot())
      → LLM 或规则模型决定：回复文本 + 零或多个 ToolCallBlock
   b. 若无工具调用 → 返回最终消息
   c. toolOrchestrator.execute(toolCalls)
      → 权限校验 → 并发/串行执行 → List<ConversationMessage(USER, ToolResultBlock)>
   d. 工具结果追加回 memory
   e. 若消息数超阈值 → SimpleContextCompactor 压缩旧历史

4. 最终 assistantMessage.plainText() 输出到控制台
   write_file 产物保存在 output/architecture-summary.md
```

---

## 四、四个关键设计原则（来自注释）

| 原则 | 体现位置 |
|---|---|
| **消息驱动循环（显式）** | `AgentEngine.executeLoop()`：主循环完全展开，不藏在框架里 |
| **统一消息流** | `ContentBlock` sealed interface：文本/工具调用/结果走同一条链 |
| **能力与治理分层** | `ToolMetadata`（声明能力）+ `PermissionPolicy`（独立治理） |
| **上下文预算意识** | `ConversationMemory` 压缩 + `ReadFileTool` 6000 字符截断 |

---

## 五、扩展点

- **新增 LLM Provider**：实现 `LlmProviderClient`，在 `ModelAdapterFactory` 添加一个 `case`
- **新增工具**：实现 `Tool` 接口，声明 `ToolMetadata`，注册到 `ToolRegistry`
- **自定义权限策略**：实现 `PermissionPolicy` 接口，替换 `WorkspacePermissionPolicy`
- **自定义压缩策略**：替换 `SimpleContextCompactor`（可改为调用 LLM 生成摘要）
- **交互式应用**：使用 `AgentEngine.chat()` 替代 `run()`，memory 跨轮共享
