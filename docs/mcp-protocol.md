# MCP 协议集成 — Java 实现文档

## 概述

MCP（Model Context Protocol）是 Anthropic 的开放标准，用于 LLM 与外部服务之间的通信。本项目实现了 MCP 协议的核心子集，允许 agent loop 动态发现和调用外部 MCP 服务器提供的工具和资源。

### 核心原语

| 原语 | 说明 | 实现状态 |
|------|------|----------|
| **Tools** | 外部服务暴露的可调用函数 | ✅ 已实现 |
| **Resources** | 外部服务提供的可读取数据 | ✅ 已实现 |
| **Prompts** | 预定义的提示模板 | ❌ 未实现 |

## 架构

```
┌─────────────────────────────────────────────────────────────┐
│                 InteractiveApplication / DemoApplication      │
│  启动时: McpConfigLoader → McpConnectionManager              │
│          → McpToolBridge → 合并到 ToolRegistry               │
│  REPL: /mcp 命令显示连接状态                                  │
└──────────┬──────────────────────────────────────────────────-┘
           │
┌──────────▼──────────────────────────────────────────────────-┐
│              McpConnectionManager (client/)                    │
│  管理 N 个 McpClient 实例（每服务器一个）                       │
│  并发批量连接: stdio 批次 3, 远程批次 10                       │
│  状态: PENDING → CONNECTED / FAILED / DISABLED                │
│  远程传输: 指数退避重连 (1s→30s, 最多 5 次)                    │
└──────────┬──────────────────────────────────────────────────-┘
           │
┌──────────▼──────────────────────────────────────────────────-┐
│                    McpClient (client/)                         │
│  initialize() → tools/list → tools/call                       │
│  resources/list → resources/read                              │
│  输出截断: MAX_OUTPUT_CHARS = 100,000                          │
└──────────┬──────────────────────────────────────────────────-┘
           │
┌──────────▼──────────────────────────────────────────────────-┐
│              McpTransport (transport/)                         │
│  ├── StdioTransport (ProcessBuilder stdin/stdout)             │
│  │   关闭: SIGTERM → 500ms → SIGKILL                          │
│  ├── LegacySseTransport (经典 SSE 双通道)                      │
│  │   GET /sse 持久流 + POST /message?sessionId=xxx            │
│  │   endpoint 超时: 60s, 响应超时: 60s                         │
│  └── StreamableHttpTransport (Streamable HTTP)                │
│      每次独立 POST, 支持 JWT OAuth 认证                         │
└───────────────────────────────────────────────────────────────┘
```

## 包结构

```
com.co.claudecode.demo.mcp/
├── McpTransportType.java          枚举: STDIO, SSE, HTTP
├── McpConnectionState.java        枚举: PENDING, CONNECTED, FAILED, DISABLED
├── McpServerConfig.java           服务器配置 record
├── McpServerConnection.java       连接状态持有者
├── McpToolInfo.java               工具信息 record (含 McpParamInfo)
├── McpResourceInfo.java           资源信息 record
├── McpNameUtils.java              命名工具: mcp__<server>__<tool>
├── McpConfigLoader.java           配置发现 + 环境变量展开
├── McpPermissionPolicy.java       MCP 感知的权限策略
│
├── protocol/
│   ├── JsonRpcRequest.java        JSON-RPC 2.0 请求
│   ├── JsonRpcResponse.java       JSON-RPC 2.0 响应
│   ├── JsonRpcError.java          JSON-RPC 错误码
│   └── SimpleJsonParser.java      零依赖 JSON 工具
│
├── transport/
│   ├── McpTransport.java          传输层接口
│   ├── StdioTransport.java        子进程传输（stdin/stdout）
│   ├── LegacySseTransport.java    经典 SSE 双通道传输（GET /sse + POST /message）
│   └── StreamableHttpTransport.java  Streamable HTTP 传输（独立 POST + JWT OAuth）
│
├── client/
│   ├── McpClient.java             MCP 客户端
│   ├── McpInitResult.java         初始化结果
│   ├── McpResourceContent.java    资源内容
│   └── McpConnectionManager.java  多服务器连接管理器
│
└── tool/
    ├── McpToolBridge.java         MCP 工具 → Tool 接口适配器
    ├── ListMcpResourcesTool.java  内建: 列出资源
    └── ReadMcpResourceTool.java   内建: 读取资源
```

## 工具命名约定

MCP 工具采用 `mcp__<normalizedServer>__<normalizedTool>` 格式命名。

```
原始服务器名:  "my.server name"
  ↓ normalizeForMcp()
规范化:        "my_server_name"     // [^a-zA-Z0-9_-] → _
  ↓ buildToolName(server, tool)
完整工具名:    "mcp__my_server_name__get_file"
```

工具类 `McpNameUtils` 提供：
- `normalizeForMcp(name)` — 字符规范化（截断到 64 字符）
- `buildToolName(server, tool)` — 构建完整工具名
- `parseToolName(fullName)` — 反向解析 → `McpToolRef(serverName, toolName)`
- `isMcpTool(name)` — 检查是否为 MCP 工具
- `isToolFromServer(name, server)` — 检查工具是否属于指定服务器
- `getMcpPrefix(server)` — 获取服务器工具前缀

## 配置格式

### 项目级配置 (`.mcp.json`)

```json
{
  "mcpServers": {
    "my-server": {
      "type": "stdio",
      "command": "node",
      "args": ["server.js", "--port", "3000"],
      "env": { "NODE_ENV": "production" }
    },
    "remote-api": {
      "type": "sse",
      "url": "http://localhost:3000/mcp",
      "headers": { "Authorization": "Bearer ${API_TOKEN}" }
    }
  }
}
```

### 用户级配置 (`~/.claude/settings.json`)

```json
{
  "mcpServers": {
    "global-server": {
      "command": "python",
      "args": ["-m", "mcp_server"]
    }
  }
}
```

### 配置合并优先级

项目级 > 用户级（同名服务器被项目级覆盖）

### 环境变量展开

- `${VAR}` — 替换为环境变量值，未找到保留原始
- `${VAR:-default}` — 替换为环境变量值，未找到使用默认值

展开字段：
- stdio: `command`, `args[]`, `env{}`
- sse/http: `url`, `headers{}`

## JSON-RPC 协议实现

### 零依赖 JSON

使用 `SimpleJsonParser` 手写 JSON 解析/构建，复用 `AnthropicProviderClient` 的模式。
支持：字符串/数字/布尔/null、对象、数组、嵌套、转义。

### MCP 握手流程

```
Client → Server:  "initialize" { protocolVersion, clientInfo, capabilities }
Server → Client:  { serverInfo, capabilities, instructions }
Client → Server:  "notifications/initialized" (通知)
```

### 工具操作

```
Client → Server:  "tools/list" {}
Server → Client:  { tools: [{ name, description, inputSchema, annotations }] }

Client → Server:  "tools/call" { name, arguments }
Server → Client:  { content: [{ type: "text", text: "..." }], isError? }
```

### 资源操作

```
Client → Server:  "resources/list" {}
Server → Client:  { resources: [{ uri, name, mimeType, description }] }

Client → Server:  "resources/read" { uri }
Server → Client:  { contents: [{ uri, mimeType, text?, blob? }] }
```

## 传输层

### StdioTransport

- 使用 `ProcessBuilder` 启动子进程
- 通过 stdin 写入 JSON-RPC 消息（每行一个 + `\n`）
- 通过 stdout 读取响应（每行一个 JSON）
- stderr 由独立守护线程消费（防止缓冲区满）
- 关闭序列：关闭 stdin → SIGTERM → 等待 500ms → SIGKILL

### LegacySseTransport（经典 SSE 双通道）

实现标准 MCP SSE 协议（Server-Sent Events），用于 xt-search 美团搜索 MCP：

1. **GET /sse** — 建立持久 SSE 流，接收 `event: endpoint` 获取 POST URL（含 sessionId）
2. **POST /message?sessionId=xxx** — 发送 JSON-RPC 请求
3. 响应通过 SSE 流中的 `data:` 行返回，按 JSON-RPC id 匹配

关键实现细节：
- SSE 读取线程必须在 `open = true` **之后**启动，否则 while 循环条件 `&& open` 会立即退出，导致 endpoint 事件永远收不到（死锁超时）
- endpoint 超时 60 秒，响应超时 60 秒，POST 超时 30 秒
- URL 解析：`base.resolve(endpointData)` + origin 安全检查

### StreamableHttpTransport（Streamable HTTP）

用于 mt-map 美团地图 MCPHub，每次独立 POST 一发一收：

- 支持动态 header 注入（JWT OAuth 认证）
- 每个请求是独立的 HTTP POST
- 请求超时 60 秒，连接超时 30 秒

## 工具桥接机制

`McpToolBridge` 将 MCP 服务器上的工具适配为 Java `Tool` 接口：

```java
// MCP 工具信息 → ToolMetadata
McpToolInfo("get_file", "Read a file", schema, params, readOnly=true)
  ↓
ToolMetadata(
  name = "mcp__server__get_file",
  description = "Read a file",
  readOnly = true,
  concurrencySafe = true,    // readOnly 工具是并发安全的
  pathDomain = NONE,         // MCP 工具不操作本地路径
  params = [ParamInfo(...)]  // 从 inputSchema 转换
)

// execute() 委托到 McpConnectionManager
Map<String, String> input → JSON 序列化 → callTool(server, tool, json)
```

## 安全模型

### McpPermissionPolicy

在原有 `WorkspacePermissionPolicy` 基础上添加 MCP 服务器级别控制：

1. 非 MCP 工具 → 委托给原有策略
2. 解析 serverName → 检查拒绝列表 → 检查允许列表
3. 拒绝列表优先于允许列表

```java
McpPermissionPolicy policy = new McpPermissionPolicy(
    delegate,
    Set.of("trusted-server"),  // 允许列表（空=全部允许）
    Set.of("blocked-server")   // 拒绝列表
);
```

## 连接管理

### 批量连接

| 传输类型 | 批次大小 | 说明 |
|---------|---------|------|
| stdio   | 3       | 本地进程启动，资源开销较大 |
| 远程    | 10      | HTTP 连接，开销较小 |

### 状态机

```
PENDING → CONNECTED    (initialize 成功)
PENDING → FAILED       (连接失败)
CONNECTED → FAILED     (连接断开)
FAILED → PENDING       (重连尝试)
* → DISABLED           (手动禁用)
```

### 重连策略（仅远程传输）

- 最大尝试次数：5
- 退避公式：`min(1000 × 2^(attempt-1), 30000)` ms
- 1s → 2s → 4s → 8s → 16s

## 内建 MCP 工具

### mcp_list_resources

列出所有已连接 MCP 服务器的资源。
- 参数：`server`（可选，过滤特定服务器）
- 只读、并发安全

### mcp_read_resource

读取指定 MCP 资源。
- 参数：`server`（必填）、`uri`（必填）
- 二进制内容标记为 `[Binary content]`，不注入上下文

## 使用示例

### 1. 配置 MCP 服务器

在项目根目录创建 `.mcp.json`：

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/path/to/dir"]
    }
  }
}
```

### 2. 启动 REPL

```bash
java -jar target/Capybara-1.0-SNAPSHOT.jar /path/to/workspace
```

启动时会自动：
1. 从 `.mcp.json` 加载配置
2. 连接 MCP 服务器
3. 发现工具和资源
4. 注册为可用工具（以 `mcp__` 前缀出现）

### 3. 查看 MCP 状态

```
> /mcp
  MCP Servers:
    filesystem [CONNECTED] transport=stdio tools=5 resources=0 version=1.0.0
```

### 4. 模型自动调用

模型会在工具列表中看到 MCP 工具（如 `mcp__filesystem__read_file`），
并在需要时自动调用。

## 与 TS 版差异

| 特性 | TS 版 | Java 版 |
|------|-------|---------|
| 传输层 | MCP SDK + 6 种传输 | JDK 手写 + 3 种 (stdio/LegacySSE/StreamableHTTP) |
| JSON 处理 | Zod schema + JSON.parse | SimpleJsonParser 手写 |
| 工具注册 | 动态（LRU 缓存 + 热更新） | 启动时加载（ToolRegistry 不可变） |
| 认证 | OAuth 2.0 + PKCE + XAA | JWT OAuth (HS256 + Client Credentials + Token Exchange) |
| 资源列表 | shouldDefer 延迟加载 | 始终加载 |
| Channel | 双向通知 + 权限协商 | 不支持 |
| 配置源 | 7 级优先级 | 2 级（项目 > 用户） |
| 重连 | 自动 + UI 状态同步 | 手动触发 |

## 测试

179 个新增单元测试，覆盖：

| 测试类 | 测试数 | 覆盖范围 |
|--------|--------|----------|
| SimpleJsonParserTest | 33 | JSON 构建/解析/转义/嵌套/数组 |
| JsonRpcMessageTest | 8 | 请求序列化/响应解析/错误码 |
| McpNameUtilsTest | 15 | 规范化/构建/解析/前缀/检查 |
| McpServerConfigTest | 8 | 工厂方法/传输类型/禁用/默认值 |
| McpServerConnectionTest | 10 | 状态转换/工具列表/资源/格式化 |
| McpConfigLoaderTest | 17 | 文件解析/环境变量/配置合并/边界 |
| StdioTransportTest | 10 | 启动/发送/接收/关闭/错误 |
| LegacySseTransportTest | 14 | SSE 流解析/endpoint 事件/URL 解析/open 竞态保护/超时 |
| StreamableHttpTransportTest | 13 | HTTP POST/JWT header 注入/超时/错误处理 |
| McpClientTest | 14 | initialize/listTools/callTool/resources/截断 |
| McpConnectionManagerTest | 12 | 批量连接/禁用/失败/查询/常量 |
| McpToolBridgeTest | 11 | 元数据/参数/execute/工厂 |
| ListMcpResourcesToolTest | 5 | 元数据/空结果/过滤 |
| ReadMcpResourceToolTest | 6 | 元数据/验证/错误处理 |
| McpPermissionPolicyTest | 8 | 委托/允许/拒绝/优先级 |
| McpIntegrationTest | 8 | 端到端: 配置→命名→JSON-RPC→桥接→权限→状态机 |
