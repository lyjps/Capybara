# 架构摘要

分析工作区: /Users/co/Downloads/aigc/claude-code-source-code
参考文件:
- README.md
- README_CN.md
- src/QueryEngine.ts
- src/Tool.ts
- src/Task.ts

## 核心判断
- 入口层和执行层被明确拆开，说明系统要同时支撑交互式场景与 headless/SDK 场景，而不是只服务一个 CLI。
- 工具协议显式暴露并发、安全和校验元数据，说明作者把“能不能做”与“怎么做”分成了不同层。
- 上下文压缩是内建机制，不是补丁。原因是 agent 一旦多轮调用工具，历史增长会非常快。
- 扩展能力通过统一工具面并入主循环，而不是为每类外部能力单独开分支流程。

## 为什么 Java demo 这样分层
- `AgentEngine` 对应原项目的 query loop，用多轮消息驱动系统前进。
- `ConversationMemory` 对应上下文窗口治理，负责 compact 而不是让模型无限背负旧历史。
- `ToolOrchestrator` 对应工具编排层，只让声明为安全的调用并发。
- `WorkspacePermissionPolicy` 对应权限层，说明安全策略不应该散落在每个工具实现里。
