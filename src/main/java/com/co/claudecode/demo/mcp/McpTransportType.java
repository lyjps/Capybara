package com.co.claudecode.demo.mcp;

/**
 * MCP 传输类型枚举。
 * <p>
 * 与 TS 版 McpServerConfig.type 对应，但只实现 Java demo 范围内的传输方式。
 */
public enum McpTransportType {

    /** 子进程 stdin/stdout 传输（默认）。 */
    STDIO,

    /** Server-Sent Events + HTTP POST 传输。 */
    SSE,

    /** Streamable HTTP 传输。 */
    HTTP
}
