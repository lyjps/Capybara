package com.co.claudecode.demo.mcp.transport;

import com.co.claudecode.demo.mcp.protocol.JsonRpcRequest;
import com.co.claudecode.demo.mcp.protocol.JsonRpcResponse;

import java.io.IOException;

/**
 * MCP 传输层接口。
 * <p>
 * 对应 TS 版 MCP SDK 的 Transport 接口。
 * 传输层负责将 JSON-RPC 消息发送到 MCP 服务器并接收响应。
 * <p>
 * 三个实现：
 * <ul>
 *   <li>{@link StdioTransport} — 子进程 stdin/stdout</li>
 *   <li>{@link StreamableHttpTransport} — Streamable HTTP (POST 一发一收)</li>
 *   <li>{@link LegacySseTransport} — 经典 SSE 双通道 (GET /sse + POST /message)</li>
 * </ul>
 */
public interface McpTransport extends AutoCloseable {

    /**
     * 启动传输层（建立连接或启动进程）。
     *
     * @throws IOException 如果启动失败
     */
    void start() throws IOException;

    /**
     * 发送 JSON-RPC 请求并等待响应。
     *
     * @param request JSON-RPC 请求
     * @return JSON-RPC 响应
     * @throws IOException 如果发送或接收失败
     */
    JsonRpcResponse sendRequest(JsonRpcRequest request) throws IOException;

    /**
     * 发送 JSON-RPC 通知（无响应）。
     *
     * @param method 方法名
     * @param params 参数 JSON 字符串（可以为 null）
     * @throws IOException 如果发送失败
     */
    void sendNotification(String method, String params) throws IOException;

    /**
     * 传输层是否打开。
     */
    boolean isOpen();

    /**
     * 关闭传输层。
     */
    @Override
    void close() throws IOException;
}
