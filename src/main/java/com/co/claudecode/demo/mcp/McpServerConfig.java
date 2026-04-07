package com.co.claudecode.demo.mcp;

import com.co.claudecode.demo.mcp.auth.McpAuthConfig;

import java.util.List;
import java.util.Map;

/**
 * MCP 服务器配置。
 * <p>
 * 对应 TS 版 McpServerConfig 的简化版本。
 * 支持 stdio、SSE、HTTP 三种传输类型。
 * <p>
 * 配置示例（.mcp.json）：
 * <pre>
 * {
 *   "mcpServers": {
 *     "my-server": {
 *       "type": "stdio",
 *       "command": "node",
 *       "args": ["server.js"],
 *       "env": { "PORT": "3000" }
 *     }
 *   }
 * }
 * </pre>
 */
public record McpServerConfig(
        String name,
        McpTransportType transportType,
        // stdio fields
        String command,
        List<String> args,
        Map<String, String> env,
        // sse/http fields
        String url,
        Map<String, String> headers,
        // auth (for http with OAuth)
        McpAuthConfig auth,
        // common
        boolean disabled
) {

    public McpServerConfig {
        args = args != null ? List.copyOf(args) : List.of();
        env = env != null ? Map.copyOf(env) : Map.of();
        headers = headers != null ? Map.copyOf(headers) : Map.of();
    }

    /**
     * 创建 stdio 类型配置。
     */
    public static McpServerConfig stdio(String name, String command, List<String> args,
                                         Map<String, String> env) {
        return new McpServerConfig(name, McpTransportType.STDIO, command, args, env,
                null, Map.of(), null, false);
    }

    /**
     * 创建 SSE 类型配置。
     */
    public static McpServerConfig sse(String name, String url, Map<String, String> headers) {
        return new McpServerConfig(name, McpTransportType.SSE, null, List.of(), Map.of(),
                url, headers, null, false);
    }

    /**
     * 创建 HTTP 类型配置。
     */
    public static McpServerConfig http(String name, String url, Map<String, String> headers) {
        return new McpServerConfig(name, McpTransportType.HTTP, null, List.of(), Map.of(),
                url, headers, null, false);
    }

    /**
     * 创建带 OAuth 认证的 HTTP 类型配置。
     */
    public static McpServerConfig httpWithAuth(String name, String url,
                                                Map<String, String> headers,
                                                McpAuthConfig auth) {
        return new McpServerConfig(name, McpTransportType.HTTP, null, List.of(), Map.of(),
                url, headers, auth, false);
    }

    /**
     * 创建已禁用的配置。
     */
    public McpServerConfig asDisabled() {
        return new McpServerConfig(name, transportType, command, args, env, url, headers, auth, true);
    }

    /**
     * 是否为本地传输（stdio）。
     */
    public boolean isLocal() {
        return transportType == McpTransportType.STDIO;
    }

    /**
     * 是否为远程传输（SSE/HTTP）。
     */
    public boolean isRemote() {
        return transportType == McpTransportType.SSE || transportType == McpTransportType.HTTP;
    }
}
