package com.co.claudecode.demo.mcp;

/**
 * MCP 服务器连接状态。
 * <p>
 * 与 TS 版 MCPServerConnection.type 对应的简化版本。
 * 状态转换：
 * <pre>
 *   PENDING → CONNECTED
 *   PENDING → FAILED
 *   CONNECTED → FAILED (连接断开)
 *   FAILED → PENDING (重连尝试)
 *   * → DISABLED (手动禁用)
 * </pre>
 */
public enum McpConnectionState {

    /** 等待连接。 */
    PENDING,

    /** 已连接，可用。 */
    CONNECTED,

    /** 连接失败。 */
    FAILED,

    /** 手动禁用。 */
    DISABLED
}
