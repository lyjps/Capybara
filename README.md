# Claude Code Java

一个用 Java 重建 Claude Code 核心设计思想的可运行 demo。

这个仓库的目标不是逐行翻译原始 TypeScript 源码，而是把更稳定、更值得迁移的架构骨架提炼出来：

- 消息驱动的 agent loop
- tool call / tool result 回写式执行
- 工具元数据与权限治理分层
- 上下文压缩而不是无界历史堆积
- 模型适配层与执行层解耦

## 为什么这个项目值得单独存在

很多“AI agent demo”只展示一次模型调用，几乎不处理真实系统最难的部分：

- 多轮消息如何闭环
- 工具调用如何治理
- 上下文如何压缩
- 模型如何替换而不重写整套执行层

Claude Code 真正有价值的地方，恰好在这些工程边界上。这个仓库就是用 Java 把这些边界重新表达出来。

## 当前能力

- 可运行的 agent engine
- 统一消息模型：文本、摘要、`tool_call`、`tool_result`
- 工具注册、权限校验、串并行编排
- compact 机制
- 基于规则的本地演示模型
- 同时支持 `OpenAI` / `Anthropic` 的模型接入骨架

## 架构映射

| 原项目思想 | 本仓库对应 |
|---|---|
| `QueryEngine` / `query.ts` | `AgentEngine` |
| `tool_use` / `tool_result` | `ToolCallBlock` / `ToolResultBlock` |
| 工具协议与元数据 | `Tool` / `ToolMetadata` |
| 工具执行编排 | `ToolOrchestrator` |
| 权限层先于能力层 | `WorkspacePermissionPolicy` |
| compact / snip / collapse 思想 | `ConversationMemory` / `SimpleContextCompactor` |
| 模型可替换 | `ModelAdapter` + provider skeleton |

## 项目结构

```text
src/main/java/com/co/claudecode/demo
├── DemoApplication.java              # 入口，负责装配 engine / tools / model
├── agent/                            # 主循环与上下文治理
├── message/                          # 统一消息协议
├── model/
│   ├── RuleBasedModelAdapter.java    # 本地可运行演示模型
│   └── llm/                          # OpenAI / Anthropic 抽象接入骨架
└── tool/                             # 工具、权限与编排
```

## 快速开始

编译：

```bash
cd /Users/co/Downloads/cc-java
mvn compile
```

运行本地规则模型：

```bash
mvn exec:java -Dexec.args="/Users/co/Downloads/aigc/claude-code-source-code"
```

运行后会把分析产物写到：

```text
output/architecture-summary.md
```

## 模型后端选择

默认后端是 `rules`，因为它最适合演示 agent loop 本身。

现在仓库已经补了 provider skeleton，可以通过环境变量切换后端装配：

```bash
export CLAUDE_CODE_DEMO_MODEL_PROVIDER=rules
```

或者：

```bash
export CLAUDE_CODE_DEMO_MODEL_PROVIDER=openai
export OPENAI_API_KEY=replace-me
```

或者：

```bash
export CLAUDE_CODE_DEMO_MODEL_PROVIDER=anthropic
export ANTHROPIC_API_KEY=replace-me
```

当前 `openai` / `anthropic` 这两条路径是“接入骨架”而不是完整生产集成，重点是把下面这些边界固定下来：

- provider 如何选择
- 请求如何标准化
- 系统提示、历史消息、工具 schema 如何进入模型层
- provider 专属差异如何被隔离

## 设计取向

### 1. 为什么保留消息流而不是直接传 DTO

因为 agent 系统的本质不是“调用一次模型”，而是持续维护一个会演化的对话状态。  
把文本、工具调用、工具结果统一建模成消息块，能让下一轮推理直接消费上一轮副作用。

### 2. 为什么工具权限不写进每个工具里

因为能力和治理不是同一个问题。  
把权限逻辑塞进工具本体，短期看似省事，长期会让安全规则四处分叉，最后很难统一演化。

### 3. 为什么 compact 是内建功能

因为多轮 agent 一定会产生历史膨胀。  
如果 compact 只是“后面再补”，系统最终会被上下文窗口反过来支配。

### 4. 为什么先做 provider skeleton 而不是直接接满 SDK

因为真正稳定的部分不是某家 SDK 的方法名，而是 provider 抽象边界。  
只有先把边界固定住，后续接 OpenAI、Anthropic 或更多模型时，主循环和工具层才能保持稳定。

## 已验证

Codex 已完成以下验证：

```bash
mvn compile
mvn exec:java -Dexec.args="/Users/co/Downloads/aigc/claude-code-source-code"
```

## 后续可扩展方向

- 把 provider skeleton 接成真实 HTTP 调用
- 给 `ToolMetadata` 增加可序列化 schema，真正传入模型
- 加入 streaming token 事件
- 增加 stop hook / retry / fallback 机制
- 增加 SDK 模式与非交互模式

## 当前生成产物

- [architecture-summary.md](/Users/co/Downloads/cc-java/output/architecture-summary.md)

