package com.co.claudecode.demo.mcp.client;

/**
 * MCP initialize 握手结果。
 *
 * @param serverName       服务器名称
 * @param serverVersion    服务器版本
 * @param instructions     服务器指令（截断到 2048 字符）
 * @param supportsTools    是否支持 tools 能力
 * @param supportsResources 是否支持 resources 能力
 * @param supportsPrompts  是否支持 prompts 能力
 */
public record McpInitResult(
        String serverName,
        String serverVersion,
        String instructions,
        boolean supportsTools,
        boolean supportsResources,
        boolean supportsPrompts
) {

    /** 指令最大长度（与 TS 版一致）。 */
    public static final int MAX_INSTRUCTIONS_LENGTH = 2048;
}
