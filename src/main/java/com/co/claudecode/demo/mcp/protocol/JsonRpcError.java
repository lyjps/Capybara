package com.co.claudecode.demo.mcp.protocol;

/**
 * JSON-RPC 2.0 错误对象。
 *
 * @param code    错误代码（如 -32600 Invalid Request, -32601 Method not found）
 * @param message 错误消息
 * @param data    可选的附加数据（原始 JSON 字符串）
 */
public record JsonRpcError(int code, String message, String data) {

    /** Parse error. */
    public static final int PARSE_ERROR = -32700;
    /** Invalid request. */
    public static final int INVALID_REQUEST = -32600;
    /** Method not found. */
    public static final int METHOD_NOT_FOUND = -32601;
    /** Invalid params. */
    public static final int INVALID_PARAMS = -32602;
    /** Internal error. */
    public static final int INTERNAL_ERROR = -32603;
    /** Connection closed (MCP custom). */
    public static final int CONNECTION_CLOSED = -32000;
    /** Session expired (MCP custom, HTTP 404 + this code). */
    public static final int SESSION_EXPIRED = -32001;
}
