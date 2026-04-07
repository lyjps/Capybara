# Code Quality Improvements

本文档记录 2026-04 进行的代码质量改进，涵盖 Bug 修复、死代码清理、架构优化和代码重复消除。

## 1. Bug 修复

### AnthropicProviderClient.buildEndpoint() URL 重复拼接

**文件**: `model/llm/AnthropicProviderClient.java`

**问题**: `buildEndpoint()` 方法中，当 `baseUrl` 已包含 `/v1` 路径时，最后一个分支仍然拼接 `/v1/messages`，导致生成 `/v1/v1/messages` 的错误 URL。两个分支代码完全相同，属于 copy-paste 遗留 Bug。

**修复**: 最后一个分支改为 `return baseUrl + "/messages"`（仅追加 `/messages`）。

## 2. 死代码清理

### LlmConversationMapper.mapTool() 方法移除

**文件**: `model/llm/LlmConversationMapper.java`

**问题**: `mapTool()` 方法在引入 Tool Deferred Loading 功能时被 `mapToolWithDeferFlag()` 完全取代，已无任何调用者。

**修复**: 删除 `mapTool()` 方法。

### ToolSearchTool requiredTermIndices 逻辑移除

**文件**: `tool/impl/ToolSearchTool.java`

**问题**: 搜索评分逻辑中保留了 `requiredTermIndices` 列表声明和相关判断，但注释已说明"简化：全部视为可选"，实际从未向列表添加元素。

**修复**: 移除 `requiredTermIndices` 声明、填充循环和 `allRequiredMatched` 变量，简化评分循环。

## 3. 消除反射 hack

### SubAgentRunner 循环依赖解决方式

**文件**: `agent/SubAgentRunner.java`, `InteractiveApplication.java`

**问题**: `InteractiveApplication` 使用 `setAccessible(true)` 反射修改 `SubAgentRunner` 的 `private final modelAdapter` 字段来解决循环依赖（SubAgentRunner → ModelAdapter → ToolRegistry → AgentTool → SubAgentRunner）。

**修复**:
- `SubAgentRunner.modelAdapter` 改为非 final 字段
- 新增 `setModelAdapter()` 公共方法，Javadoc 说明循环依赖原因
- `InteractiveApplication` 中反射代码替换为 `subAgentRunner.setModelAdapter(modelAdapter)`

## 4. 魔法数字提取为命名常量

### InteractiveApplication 常量

**文件**: `InteractiveApplication.java`

提取 6 个常量：
```java
private static final int CONTEXT_WINDOW_TOKENS = 200_000;
private static final int MAX_OUTPUT_TOKENS = 16_384;
private static final int AUTO_COMPACT_BUFFER = 13_000;
private static final int MAIN_AGENT_MAX_TURNS = 12;
private static final int SUB_AGENT_MAX_CONCURRENCY = 4;
private static final int TOOL_ORCHESTRATOR_CONCURRENCY = 4;
```

替换了 `main()` 和 `resetMemory()` 中的所有硬编码数值，包括 `/clear` 命令中的 `AgentEngine` 构造参数。

### SubAgentRunner 常量

**文件**: `agent/SubAgentRunner.java`

提取 3 个常量：
```java
private static final int SUB_AGENT_CONTEXT_MAX_MESSAGES = 24;
private static final int SUB_AGENT_COMPACTOR_THRESHOLD = 12;
private static final int SUB_TOOL_ORCHESTRATOR_CONCURRENCY = 2;
```

替换了 `executeAgent()` 中 `ToolOrchestrator` 和 `ConversationMemory` 构造器的硬编码参数。

## 5. 代码重复消除

### SubAgentRunner.executeWithTiming()

**文件**: `agent/SubAgentRunner.java`

**问题**: `runSync()` 和 `runAsync()` 中的计时、try-catch、结果构建逻辑完全重复（约 15 行 × 2）。

**修复**: 提取 `executeWithTiming()` 私有方法，`runSync()` 直接调用，`runAsync()` 在 `CompletableFuture.supplyAsync()` lambda 中调用。

### StreamableHttpTransport.applyHeaders()

**文件**: `mcp/transport/StreamableHttpTransport.java`

**问题**: `sendRequest()` 和 `sendNotification()` 中的静态 header 注入、动态 header 注入、session ID 注入逻辑完全相同（约 15 行 × 2）。

**修复**: 提取 `applyHeaders(HttpRequest.Builder)` 私有方法，两个发送方法各调用一次。

### InteractiveApplication Memory 创建统一

**文件**: `InteractiveApplication.java`

**问题**: `main()` 和 `resetMemory()` 中 `ConversationMemory` 创建 + system prompt 注入逻辑重复。

**修复**: `main()` 中直接调用 `resetMemory()` 而不是重复 Memory 创建代码。

## 6. 低效模式修正

### ToolRegistry.size() 方法

**文件**: `tool/ToolRegistry.java`, `agent/AgentEngine.java`

**问题**: `AgentEngine.isStreamingToolExecutionEnabled()` 使用 for 循环遍历 `toolRegistry.allTools()` 手动计数，而底层 `LinkedHashMap` 本身有 O(1) 的 size 信息。

**修复**: `ToolRegistry` 新增 `size()` 方法（委托给 `toolsByName.size()`），`AgentEngine` 中改为 `toolRegistry.size()`。

### LlmConversationMapper 枚举比较优化

**文件**: `model/llm/LlmConversationMapper.java`

**问题**: `msg.role().name().equals("SYSTEM")` 将枚举转为字符串再比较，不利于类型安全和 IDE 重构支持。

**修复**: 改为 `msg.role() == MessageRole.SYSTEM`（枚举直接引用比较）。新增 `MessageRole` 导入。

## 7. JSON 工具方法统一

### AnthropicProviderClient.escapeJson() 委托

**文件**: `model/llm/AnthropicProviderClient.java`

**问题**: `escapeJson()` 方法是 `SimpleJsonParser.escapeJson()` 的简化重复实现（缺少 `\b`、`\f`、控制字符处理）。

**修复**: 保留 `escapeJson()` 私有方法签名（避免修改 16 处调用点），内部委托给 `SimpleJsonParser.escapeJson()`。同时获得了更完备的转义能力。

> **未合并的方法**: `findClosingBrace()`、`skipPastString()`、`extractJsonStringValue()` 是 SSE 增量解析专用方法，与 `SimpleJsonParser` 的完整 JSON 解析场景不同，保持独立。

## 验证

所有改动通过完整测试回归验证：

```
Tests run: 723, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 改动文件清单

| 文件 | 改动类型 |
|------|---------|
| `model/llm/AnthropicProviderClient.java` | Bug 修复 + JSON 委托 |
| `model/llm/LlmConversationMapper.java` | 死代码 + 枚举比较优化 |
| `tool/impl/ToolSearchTool.java` | 死代码清理 |
| `agent/SubAgentRunner.java` | 反射消除 + 常量提取 + 代码去重 |
| `InteractiveApplication.java` | 反射消除 + 常量提取 + Memory 去重 |
| `mcp/transport/StreamableHttpTransport.java` | Header 构建去重 |
| `tool/ToolRegistry.java` | 新增 size() 方法 |
| `agent/AgentEngine.java` | 使用 ToolRegistry.size() |
