# Tool Deferred Loading 实现文档

## 概述

Tool Deferred Loading（工具延迟加载）是一种优化机制，用于在 LLM API 调用时减少 prompt 大小。当注册的工具数量很多（尤其是 MCP 动态工具）时，将所有工具的 schema 全部发送给模型会消耗大量 context window。该机制通过延迟发送工具 schema，仅在模型通过 `ToolSearch` 工具"发现"后才加载对应 schema，从而显著减少 token 消耗。

## 设计来源

该实现参考了 Claude Code TypeScript 源码中的以下核心文件：
- `toolSearch.ts` — 工具搜索模式判断、过滤、发现名提取
- `ToolSearchTool.ts` — 工具搜索工具实现（关键词搜索 + select 模式）
- `prompt.ts` — system prompt 中工具搜索说明的注入
- `constants.ts` — 模式常量定义
- `claude.ts` — agent 主循环中的工具过滤集成

## 架构概览

```
用户消息 → LlmBackedModelAdapter.nextReply()
           ↓
    ToolSearchUtils.filterToolsForApi(allTools, conversation)
           ↓
    ┌──────────────────────────────────────────┐
    │ 过滤逻辑:                                │
    │  • 非延迟工具 → 始终包含                  │
    │  • ToolSearch → 始终包含                  │
    │  • 延迟工具 → 仅在已发现时包含             │
    │  • 已发现 = 消息历史中有 tool_reference    │
    └──────────────────────────────────────────┘
           ↓
    LlmConversationMapper.toRequest(conversation, model, filteredTools, allTools)
           ↓
    模型回复（可能调用 ToolSearch 来发现更多工具）
           ↓
    ToolOrchestrator.execute() — 包含 schema-not-sent 提示
```

## 核心概念

### 1. ToolSearchMode（工具搜索模式）

通过环境变量 `ENABLE_TOOL_SEARCH` 控制（与 TS 原版一致）：

| 模式 | 值 | 行为 |
|------|-----|------|
| `TST` | `"tst"` | 始终启用延迟加载 |
| `TST_AUTO` | `"tst-auto"` | 当工具 schema 总量超过阈值时自动启用 |
| `STANDARD` | 未设置或其他值 (默认) | 不启用延迟加载，所有工具 schema 全量发送 |

### 2. isDeferredTool()（判断工具是否延迟）

优先级链（从高到低）：
1. `alwaysLoad = true` → **永不延迟**（如内置工具 list_files、read_file）
2. `toolName == "ToolSearch"` → **永不延迟**（ToolSearch 自身必须始终可用）
3. `isMcp = true` → **始终延迟**（所有 MCP 工具默认延迟）
4. `shouldDefer = true` → **延迟**（手动标记的延迟工具）
5. 以上都不满足 → **不延迟**

### 3. Auto Threshold（自动阈值）

在 `TST_AUTO` 模式下，仅当延迟工具的 schema 总字符数超过 context window 的 10% 时才启用：

```
threshold = DEFAULT_CONTEXT_WINDOW × AUTO_THRESHOLD_PERCENTAGE
          = 200,000 × 0.10
          = 20,000 tokens ≈ 50,000 chars (按 2.5 chars/token 估算)
```

### 4. tool_reference 协议

ToolSearch 返回的结果使用 `[tool_reference] tool_name` 文本格式标记发现的工具。同时支持结构化的 `ToolReferenceBlock` 内容块。

## 实现细节

### 新增/修改的文件

#### 核心工具类

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `ToolSearchUtils.java` | **新建** | 工具延迟加载核心工具类，包含所有判断、过滤、发现逻辑 |
| `ToolSearchTool.java` | **新建** | ToolSearch 工具实现，支持关键词搜索和 select 模式 |

#### 消息协议

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `ToolReferenceBlock.java` | **新建** | 新的 ContentBlock 变体，承载 tool_reference 内容 |
| `ContentBlock.java` | **修改** | sealed interface 增加 ToolReferenceBlock permits |
| `ConversationMessage.java` | **修改** | 新增 toolReferences() 辅助方法 |

#### 元数据扩展

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `ToolMetadata.java` | **修改** | record 增加 isMcp, shouldDefer, alwaysLoad, searchHint 字段 |
| `LlmRequest.java` | **修改** | ToolSchema record 增加 deferLoading 标志位 |

#### 模型层集成

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `LlmBackedModelAdapter.java` | **修改** | nextReply() 中调用 filterToolsForApi() 过滤工具 |
| `LlmConversationMapper.java` | **修改** | 新增接受 filteredTools + allTools 的 toRequest() 重载 |

#### 工具执行层

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `ToolOrchestrator.java` | **修改** | 增加 currentConversation 字段，错误路径追加 schema-not-sent 提示 |
| `AgentEngine.java` | **修改** | 工具执行前设置 currentConversation 快照 |

#### MCP 工具标记

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `McpToolBridge.java` | **修改** | metadata 构造设置 isMcp=true |
| `MappedToolRegistry.java` | **修改** | createTool() 中 metadata 设置 isMcp=true |

### ToolSearchUtils 核心方法

```java
public final class ToolSearchUtils {

    // 常量
    static final String TOOL_SEARCH_TOOL_NAME = "ToolSearch";
    static final double AUTO_THRESHOLD_PERCENTAGE = 0.10;
    static final int DEFAULT_CONTEXT_WINDOW = 200_000;

    // 判断工具是否应被延迟加载
    static boolean isDeferredTool(Tool tool);

    // 获取当前工具搜索模式 (TST / TST_AUTO / STANDARD)
    static ToolSearchMode getToolSearchMode();

    // 判断工具搜索是否启用
    static boolean isToolSearchEnabled(Collection<Tool> allTools);

    // 从消息历史中提取已发现的工具名集合
    static Set<String> extractDiscoveredToolNames(List<ConversationMessage> messages);

    // 过滤 API 调用时发送的工具列表
    static List<Tool> filterToolsForApi(Collection<Tool> allTools,
                                         List<ConversationMessage> conversation);

    // 判断工具是否会被延迟加载（用于 schema deferLoading 标记）
    static boolean willDeferLoading(Tool tool, Collection<Tool> allTools);

    // 构建 schema-not-sent 提示（当工具被调用但 schema 未发送时）
    static String buildSchemaNotSentHint(Tool tool,
                                          List<ConversationMessage> conversation,
                                          Collection<Tool> allTools);

    // 检查是否超过自动阈值（TST_AUTO 模式用）
    static boolean checkAutoThreshold(Collection<Tool> tools);
}
```

### ToolSearchTool 搜索算法

#### 1. Select 模式

以 `select:` 前缀触发，支持逗号分隔多工具名：

```
query: "select:mcp__mt_map__geo,mcp__mt_map__nearby"
→ 直接匹配并返回这两个工具的 tool_reference
```

#### 2. 关键词搜索模式

对每个延迟工具计算匹配得分：

| 匹配位置 | 匹配类型 | 得分 |
|----------|---------|------|
| 工具全名 | 精确匹配 | 12 |
| 工具名分段（`__` 分隔） | 精确匹配 | 10 |
| 工具全名 | 包含匹配 | 6 |
| 工具名分段 | 包含匹配 | 5 |
| searchHint（逗号分隔） | 包含匹配 | 4 |
| description（按空格分词） | 单词包含 | 2 |

查询词按空格分割，每个词独立计分后求和。结果按得分降序排列，默认返回前 10 个。

### filterToolsForApi 过滤流程

```java
static List<Tool> filterToolsForApi(Collection<Tool> allTools,
                                     List<ConversationMessage> conversation) {
    // 1. 判断工具搜索是否启用
    if (!isToolSearchEnabled(allTools)) {
        // 未启用 → 排除 ToolSearch 自身，其余全部发送
        return allTools.stream()
            .filter(t -> !TOOL_SEARCH_TOOL_NAME.equals(t.metadata().name()))
            .toList();
    }

    // 2. 启用 → 提取已发现的工具名
    Set<String> discovered = extractDiscoveredToolNames(conversation);

    // 3. 过滤
    return allTools.stream()
        .filter(t -> {
            if (!isDeferredTool(t)) return true;         // 非延迟 → 包含
            if (isToolSearchTool(t)) return true;        // ToolSearch → 包含
            return discovered.contains(t.metadata().name()); // 延迟 → 仅已发现
        })
        .toList();
}
```

### Schema-Not-Sent 提示机制

当模型尝试调用一个延迟工具但该工具的 schema 未被发送时，ToolOrchestrator 在错误消息中追加提示：

```
This tool's schema was not sent in this request because tool search is enabled.
The model should call ToolSearch with query "select:<toolName>" to discover
and load the tool schema first.
```

这引导模型先调用 ToolSearch 来发现工具，而不是直接猜测参数格式。

## 向后兼容性

所有修改都保持向后兼容：

1. **ToolMetadata** — 新增 7-arg 和 8-arg 向后兼容构造器，新字段默认为 `false`/`""` 
2. **LlmRequest.ToolSchema** — 新增 3-arg 向后兼容构造器，`deferLoading` 默认 `false`
3. **LlmConversationMapper** — 原 `toRequest()` 方法签名不变，内部委托给新重载
4. **默认模式** — `STANDARD` 模式下行为与修改前完全一致，不会延迟任何工具

## 测试覆盖

新增 50 个测试（总测试数从 544 增至 594）：

| 测试类 | 测试数 | 覆盖范围 |
|--------|--------|---------|
| `ToolSearchUtilsTest` | 19 | isDeferredTool 优先级链、extractDiscoveredToolNames、filterToolsForApi、checkAutoThreshold、buildSchemaNotSentHint、willDeferLoading |
| `ToolSearchToolTest` | 19 | metadata 属性、select 单选/多选/不存在/非延迟/空格处理、关键词搜索（名称/描述/searchHint）、max_results 限制、空查询/缺失查询/无效参数、description 内容 |
| `ToolDeferredLoadingIntegrationTest` | 12 | ToolReferenceBlock 渲染、ConversationMessage 提取、ToolSchema deferLoading 标志、ToolMetadata 向后兼容、LlmConversationMapper 过滤集成、端到端发现流程 |

## 配置

### 环境变量

| 变量名 | 值 | 说明 |
|--------|-----|------|
| `ENABLE_TOOL_SEARCH` | `tst` | 始终启用延迟加载 |
| `ENABLE_TOOL_SEARCH` | `tst-auto` | 自动模式（推荐） |
| `ENABLE_TOOL_SEARCH` | 未设置 | 禁用（默认） |

### 推荐配置

对于连接了大量 MCP 服务器的场景（如美团搜索 + 美团地图 = 80+ 工具），建议设置：

```bash
export ENABLE_TOOL_SEARCH=tst-auto
```

这样当 MCP 工具 schema 总量超过 context window 的 10% 时自动启用延迟加载，否则保持全量发送。
