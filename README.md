# Claude Code Java Demo

这个项目不是把原仓库逐行翻译成 Java，而是抽取更稳定的设计骨架，再用一个能运行的 demo 表达出来。

## 目标

- 保留原项目最核心的思想：消息驱动的 agent loop。
- 保留工具系统的边界：工具本身负责能力，权限层负责治理。
- 保留上下文治理：历史不能无限增长，所以需要压缩。
- 保留工程上的可扩展性：模型、工具、权限都可以替换。

## 设计映射

| 原项目思想 | Java demo 对应 |
|---|---|
| `QueryEngine`/`query.ts` 驱动多轮 agent loop | `AgentEngine` |
| `tool_use` / `tool_result` 作为消息的一部分 | `ContentBlock` / `ConversationMessage` |
| 工具具备并发、安全、读写等元数据 | `Tool` / `ToolMetadata` |
| 工具执行前先过权限与输入校验 | `ToolOrchestrator` / `WorkspacePermissionPolicy` |
| 对话过长时做 compact | `ConversationMemory` / `SimpleContextCompactor` |
| 模型与执行层解耦 | `ModelAdapter` / `RuleBasedModelAdapter` |

## 为什么 demo 采用规则模型

原项目真正复杂的部分是“循环和边界”，不是“某个具体模型 SDK 的调用细节”。

所以这个 demo 故意用 `RuleBasedModelAdapter` 代替真实 LLM。这样做的目的不是追求智能程度，而是把下面这条主链清晰暴露出来：

1. 助手输出消息。
2. 消息里可能包含 `tool_call`。
3. 编排层执行工具并写回 `tool_result`。
4. 历史必要时 compact。
5. 下一轮继续读取“消息”，而不是读取某个分散的全局状态。

## 运行方式

默认会分析传入的工作区，并把产物写到当前项目的 `output/architecture-summary.md`。

```bash
cd /Users/co/Downloads/cc-java
mvn compile
mvn exec:java -Dexec.args="/Users/co/Downloads/aigc/claude-code-source-code"
```

也可以带自定义目标：

```bash
mvn exec:java -Dexec.args="/Users/co/Downloads/aigc/claude-code-source-code 请分析这个仓库的核心架构，并输出结构化摘要"
```

## 刻意省略的部分

- 真正的网络模型调用
- MCP / 插件生态
- 遥测与远程配置
- UI / REPL 层
- 复杂的恢复逻辑与故障注入

这些部分在原项目里很重要，但对于“解释核心设计思想的 demo”来说，优先级低于主循环、工具协议和上下文治理。
