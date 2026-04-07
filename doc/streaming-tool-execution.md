# Streaming Tool Execution 实现文档

## 概述

Streaming Tool Execution（流式工具执行）是一种性能优化机制，允许在模型 SSE 流输出过程中，每当一个 `tool_use` content block 完成（`content_block_stop` 事件），就立即开始执行该工具，与模型继续输出下一个 content block 并行。这显著减少了端到端延迟——不再需要等待整个 SSE 流结束后才批量执行工具。

## 设计来源

该实现参考了 Claude Code TypeScript 源码中的以下核心文件：
- `StreamingToolExecutor.ts` — 核心流式工具执行器，状态机和调度逻辑
- `query.ts` — agent 主循环中的流式/经典路径切换
- `streamingToolCallback` 相关代码 — SSE 流中 tool_use block 完成时的回调通知机制

## 架构概览

```
SSE 线程 (AnthropicProviderClient.parseSseStream)
  │
  ├─ content_block_delta (text) → StreamCallback.onTextToken() → 终端打印
  │
  ├─ content_block_stop (tool_use) → StreamingToolCallback.onToolUseComplete()
  │                                      │
  │                                      ↓
  │                               StreamingToolExecutor.addTool()
  │                                      │
  │                                      ↓  synchronized(schedulingLock)
  │                               processQueue() → 提交到线程池
  │                                      │
  │                                      ↓
  │                               Pool thread: executeTool()
  │                                 → ToolOrchestrator.executeSingleTool()
  │                                      │
  │                                      ↓  finally { processQueue(); }
  │                               下一个 QUEUED 工具开始执行
  │
  ├─ message_stop → parseSseStream() 返回
  │
  ↓
AgentEngine.executeLoop()
  │
  ├─ streamingToolExecutor.awaitAllResults()  ← 按插入顺序 join
  │
  ↓
memory.appendAndCompact(toolResult) × N
```

## 核心概念

### 1. 状态机

每个被跟踪的工具（`TrackedTool`）遵循以下状态转换：

```
QUEUED → EXECUTING → COMPLETED
                   → FAILED
```

- **QUEUED**: `addTool()` 时创建，等待调度
- **EXECUTING**: `processQueue()` 调度后，提交到线程池执行
- **COMPLETED**: 工具执行成功完成
- **FAILED**: 工具执行抛异常或被 discard

### 2. 并发模型

对应 TS 原版的并发控制逻辑：

- **concurrent-safe 工具**（`concurrencySafe=true`）：可多个并行执行。调度时仅需检查没有独占工具在运行。
- **非 concurrent-safe 工具**（`concurrencySafe=false`）：独占执行。必须等所有其他工具完成后才能开始，且执行期间阻止其他工具启动。

调度状态变量（在 `schedulingLock` 内保护）：
- `activeConcurrentCount`: 当前并行执行的 concurrent-safe 工具数
- `exclusiveActive`: 是否有独占工具在执行

### 3. 调度触发点

`processQueue()` 在两个地方被调用：
1. `addTool()` — SSE 读取线程，新工具入队后立即尝试调度
2. `executeTool()` 的 `finally` 块 — 线程池工作线程，一个工具执行完成后触发下一个

### 4. 结果收集

`awaitAllResults()` 按**插入顺序**（不是完成顺序）逐个对 `CompletableFuture` 调用 `.join()`。这确保工具结果在对话历史中的顺序与模型输出中的 tool_use 顺序一致。

### 5. Feature Gate

通过环境变量 `ENABLE_STREAMING_TOOL_EXECUTION` 控制：

| 值 | 行为 |
|---|---|
| `enabled` / `true` / `1` | 强制启用（始终使用流式路径） |
| `disabled` / `false` / `0` | 强制禁用（始终使用经典路径）|
| `auto` | 自动判断：`streamCallback != null && toolCount > 0` 时启用 |
| 未设置 | 默认 `DISABLED`（向后兼容） |

## 新增文件

### `tool/streaming/StreamingToolExecutor.java`

核心流式工具执行器。关键设计：

```java
public final class StreamingToolExecutor implements AutoCloseable {
    // 内部状态
    private final CopyOnWriteArrayList<TrackedTool> tools;
    private final Object schedulingLock;
    private int activeConcurrentCount;
    private boolean exclusiveActive;
    private volatile boolean discarded;

    // 核心 API
    public void addTool(ToolCallBlock block);          // SSE 线程调用
    public List<ConversationMessage> awaitAllResults(); // 主线程调用
    public void discard();                              // SSE 失败时清理

    // 内部调度
    void processQueue();                     // synchronized(schedulingLock)
    private boolean canExecute(TrackedTool); // 并发规则判断
    private void executeTool(TrackedTool);   // 线程池中执行
}
```

**线程安全保证**：
- `CopyOnWriteArrayList` 保护 tools 列表的读写
- `schedulingLock` 保护调度状态（activeConcurrentCount, exclusiveActive）
- `volatile` 修饰 discarded 标志和 TrackedTool.status
- `CompletableFuture` 提供跨线程的结果传递

### `tool/streaming/StreamingToolCallback.java`

扩展 `StreamCallback`，增加工具完成通知。独立接口继承，不破坏 `@FunctionalInterface`：

```java
public interface StreamingToolCallback extends StreamCallback {
    void onToolUseComplete(ToolCallBlock toolCall);
}
```

### `tool/streaming/StreamingToolConfig.java`

Feature gate 配置类：

```java
public final class StreamingToolConfig {
    public static final String ENV_KEY = "ENABLE_STREAMING_TOOL_EXECUTION";
    public enum Mode { ENABLED, DISABLED, AUTO }

    public static Mode getMode();
    public static boolean isEnabled(StreamCallback callback, int toolCount);
    public static boolean isEnabledOptimistic();
}
```

### `tool/streaming/package-info.java`

包级文档注释。

## 修改文件

### `AnthropicProviderClient.java`

在 `parseSseStream()` 的 `content_block_stop` 处理块中，增加 `instanceof` 检查：

```java
if (callback instanceof StreamingToolCallback stc) {
    stc.onToolUseComplete(new ToolCallBlock(tcd.id(), tcd.name(), tcd.input()));
}
```

仅当 callback 是 `StreamingToolCallback` 时触发，普通 `StreamCallback` 完全不受影响。

### `ToolOrchestrator.java`

新增 `executeSingleTool()` public 方法，供 `StreamingToolExecutor` 调用：

```java
public ConversationMessage executeSingleTool(ToolCallBlock call,
                                              ToolExecutionContext context,
                                              Consumer<String> eventSink) {
    return executeSingle(call, context, eventSink);
}
```

### `ModelAdapter.java`

接口新增带 callback 的 default 重载：

```java
default ConversationMessage nextReply(List<ConversationMessage> conversation,
                                      ToolExecutionContext context,
                                      StreamCallback callback) {
    return nextReply(conversation, context);  // 向后兼容
}
```

### `LlmBackedModelAdapter.java`

提取 `doNextReply()` 私有方法，原 `nextReply()` 委托到它。新增重载使用 `callbackOverride` 参数替换默认 streamCallback。

### `AgentEngine.java`

核心改动：

1. **新增字段**: `StreamCallback streamCallback`, `ToolRegistry toolRegistry`, `volatile StreamingToolExecutor streamingToolExecutor`
2. **新增 9 参数构造函数**（保留 5 参数和 7 参数向后兼容）
3. **`executeLoop()` 流式分支**: 当 `isStreamingToolExecutionEnabled()` 返回 true 时，使用 `callModelWithStreamingTools()` 替代直接 `modelAdapter.nextReply()`
4. **`callModelWithStreamingTools()`**: 创建本轮 `StreamingToolExecutor` 和 `StreamingToolCallback`，将 callback 传给 `modelAdapter.nextReply()` 的新重载
5. **结果收集**: SSE 流结束后，如果 `streamingToolExecutor.hasTools()`，则调用 `awaitAllResults()` 收集结果；否则回退到经典路径

### `InteractiveApplication.java`

两处 `AgentEngine` 构造更新为 9 参数版本，传入 `streamCallback` 和 `toolRegistry`。

## 向后兼容性

| 组件 | 兼容策略 |
|------|---------|
| `StreamCallback` | `@FunctionalInterface` 不变，`StreamingToolCallback` 是独立子接口 |
| `AgentEngine` | 旧 5 参数和 7 参数构造函数保持不变（streamCallback=null → 经典路径）|
| `ModelAdapter` | 新增 default 方法重载，现有实现不受影响 |
| `AnthropicProviderClient` | 仅 `instanceof` 检查，不影响普通 StreamCallback |
| Feature gate | 默认 DISABLED，未设置环境变量时行为与改动前完全一致 |

## 测试覆盖

| 测试类 | 测试数 | 覆盖范围 |
|--------|--------|---------|
| `StreamingToolExecutorTest` | 12 | 状态机转换、并发调度（concurrent-safe 并行 / exclusive 串行）、按序结果收集、discard 清理、空队列、单工具快路径、错误处理 |
| `StreamingToolCallbackTest` | 7 | 接口继承关系、instanceof 检查、onToolUseComplete 通知、多次调用顺序 |
| `StreamingToolConfigTest` | 8 | Mode 枚举值、环境变量解析、isEnabled 条件判断、ENV_KEY 常量 |
| `StreamingToolIntegrationTest` | 14 | AgentEngine 路径选择、ToolOrchestrator 集成、并行/独占工具、StreamingToolCallback 路由、参数传递、discard 容错、经典/流式等价性、Feature gate |

总计 **41** 新测试，全部通过。全量回归 **635** 测试全部通过。

## 启用方式

```bash
# 启用流式工具执行
ENABLE_STREAMING_TOOL_EXECUTION=enabled java -jar target/Capybara-1.0-SNAPSHOT.jar

# 自动模式（有 streamCallback 和工具时自动启用）
ENABLE_STREAMING_TOOL_EXECUTION=auto java -jar target/Capybara-1.0-SNAPSHOT.jar

# 禁用（默认行为）
java -jar target/Capybara-1.0-SNAPSHOT.jar
```
