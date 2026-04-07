# Skill 系统（技能系统）

用户配置的技能加载与执行系统。Skill 是带有 YAML frontmatter 元数据的 Markdown 文件，定义可复用的 prompt 模板。

## 概述

Skill 系统允许用户定义自定义"技能"——存储为 `.md` 文件的可复用 prompt 模板。每个 skill 可以：
- **由模型调用**：通过 `Skill` 工具
- **由用户触发**：通过 REPL 中的 `/skill_name` 斜杠命令

本实现仅涵盖用户配置的 skill，不包含内置 skill 和插件安装能力。

## Skill 文件格式

支持两种文件布局：

### 扁平模式

直接在 skills 目录下放置 `.md` 文件：

```
skills/
  my-skill.md
  another-skill.md
```

### 目录模式

每个 skill 是一个子目录（或符号链接），内含 `SKILL.md` 文件：

```
skills/
  旅游规划/
    SKILL.md
    templates/          ← 可选的附属文件
  聚餐管家/
    SKILL.md
```

目录模式下，skill 名称默认为**父目录名**（如 `旅游规划`），除非 frontmatter 中显式指定了 `name` 字段。

### 文件内容格式

每个 skill 文件（`.md` 或 `SKILL.md`）是带有可选 YAML frontmatter 的 Markdown 文件：

```markdown
---
name: my-skill
description: "这个 skill 做什么"
when_to_use: "什么场景下使用这个 skill"
allowed-tools: [read_file, write_file]
model: sonnet
context: inline
arguments:
  - name: target
    description: "要处理的目标"
    required: true
---

这里是 prompt 模板正文。

使用 $ARGUMENTS 引用用户传入的参数。
```

### Frontmatter 字段

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `name` | string | 否 | 从文件名推导（去掉 `.md`） | Skill 标识名 |
| `description` | string | 否 | `""` | 人类可读的描述 |
| `when_to_use` / `when-to-use` | string | 否 | `""` | 模型应在何时使用此 skill |
| `allowed-tools` / `allowed_tools` | string[] | 否 | `null`（允许所有工具） | Skill 可访问的工具（fork 模式下） |
| `model` | string | 否 | `null` | fork 模式的首选模型 |
| `context` | `inline` \| `fork` | 否 | `inline` | 执行模式 |
| `arguments` | object[] | 否 | `[]` | 参数定义列表 |

### 参数定义

每个参数包含：
- `name`（必填）— 参数名称
- `description`（可选）— 参数用途描述
- `required`（可选，默认 `false`）— 参数是否必填

### Prompt 模板

Markdown 正文（frontmatter 之后的内容）即为 prompt 模板。使用 `$ARGUMENTS` 作为占位符，在执行时被用户提供的参数替换。

## Skill 目录

Skill 按优先级顺序从以下两个目录加载：

1. **用户级**：`~/.claude/skills/*.md`
2. **项目级**：`<workspaceRoot>/.claude/skills/*.md`

项目级 skill **覆盖**同名的用户级 skill。

## 执行模式

### Inline 模式（默认）

Skill 解析后的 prompt 作为工具结果返回，包裹在 `<skill>` 标签中。模型在当前对话上下文中处理此 prompt。

```xml
<skill name="my-skill">
解析后的 prompt 内容...
</skill>
```

### Fork 模式

通过 `SubAgentRunner` 创建子 Agent，在独立上下文中执行 skill prompt。子 Agent 拥有：
- 独立的 `ConversationMemory`
- 按 `allowedTools` 过滤的 `ToolRegistry`
- 独立的 `AgentEngine` 实例

子 Agent 结果以 JSON 格式返回：
```json
{
  "status": "completed",
  "skill": "my-skill",
  "mode": "fork",
  "content": "子 Agent 输出内容..."
}
```

## 架构设计

### 包：`com.co.claudecode.demo.skill`

| 类 | 职责 |
|----|------|
| `SkillDefinition` | 不可变 record — 解析后的 skill 元数据 + prompt 模板 |
| `SkillLoader` | 文件扫描器和 YAML frontmatter 解析器（零外部依赖） |
| `SkillRegistry` | 内存注册表，支持按名查找和系统提示词列表生成 |
| `SkillTool` | `Tool` 接口实现 — 模型可调用的 skill 执行入口 |

### 数据流

```
启动时:
  SkillLoader.loadAll(workspaceRoot)
    ├─ ~/.claude/skills/*.md → 解析 → SkillDefinition (USER)
    └─ <project>/.claude/skills/*.md → 解析 → SkillDefinition (PROJECT)
    → SkillRegistry（项目级按名覆盖用户级）
    → SkillTool 注册到 ToolRegistry
    → System Prompt 注入 skill 列表

模型调用路径:
  Model → Skill({skill: "name", args: "..."})
    → SkillTool.execute()
    → inline: 返回 prompt 内容作为 ToolResult
    → fork: SubAgentRunner.runSync() → 返回结果

用户斜杠命令路径:
  用户输入 "/skill_name args..."
    → InteractiveApplication 命令处理器
    → SkillRegistry.findByName()
    → resolvePrompt(args)
    → engine.chat(prompt)
```

### YAML 解析器

`SkillLoader.parseFrontmatter()` 是一个零依赖的 YAML 解析器，支持：
- 简单键值对：`key: value`
- 带引号的字符串：`key: "value"` 或 `key: 'value'`
- 行内列表：`key: [a, b, c]`
- 缩进列表：`key:` 后跟 `- item` 行
- 嵌套对象列表（用于 arguments）：`- name: xxx` 后跟缩进的子属性
- 注释：以 `#` 开头的行
- 布尔值：`true/false/yes/no`

### 集成点

1. **InteractiveApplication.java**：
   - 初始化阶段通过 `SkillLoader.loadAll()` 加载 skill
   - 创建 `SkillRegistry` 和 `SkillTool`
   - 将 `SkillTool` 注册到工具列表（仅当存在 skill 时）
   - 处理 `/skills` 命令列出已加载的 skill
   - 处理 `/skill_name args` 斜杠命令，直接调用 skill
   - 将 `SkillRegistry` 传递给 `SystemPromptBuilder.buildMainPrompt()`

2. **SystemPromptBuilder.java**：
   - `skillListingSection(SkillRegistry)` — 生成格式化的 skill 列表
   - `buildMainPrompt(..., SkillRegistry)` — 新增 6 参数重载，包含 skill 列表
   - 旧版 5 参数 `buildMainPrompt()` 保留，向后兼容（委托到新版，传入 `null`）
   - `availableToolsSection()` — 当注册了 Skill 工具时包含 "Skill" 条目

## 测试

5 个测试类共 80 个测试：

| 测试类 | 测试数 | 覆盖范围 |
|--------|--------|----------|
| `SkillDefinitionTest` | 14 | Record 构造、默认值、校验、枚举、prompt 解析 |
| `SkillLoaderTest` | 33 | 文件解析、YAML frontmatter、目录扫描、子目录 SKILL.md 加载、混合模式、边界情况 |
| `SkillRegistryTest` | 15 | 按名查找、列表生成、skill 计数、重名处理 |
| `SkillToolTest` | 13 | Inline/Fork 执行、校验、元数据、错误处理 |
| `SkillIntegrationTest` | 13 | 端到端：加载 → 注册 → 执行 → 系统提示词，含子目录模式和混合模式 |

## 向后兼容性

- `SystemPromptBuilder.buildMainPrompt()` 旧版 5 参数签名保留（委托到新版，`skillRegistry` 传 `null`）
- 当不存在 skill 文件时，不注册 `SkillTool`，系统行为与之前完全一致
- `SkillTool` 仅在 `SkillRegistry.hasSkills()` 为 `true` 时才添加到工具列表
- `InteractiveApplication` 中 `resetMemory()` 正确传递 `SkillRegistry`，确保 `/clear` 命令后 skill 仍可用
