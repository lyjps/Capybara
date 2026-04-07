package com.co.claudecode.demo.mcp.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON-RPC 2.0 请求消息。
 * <p>
 * MCP 协议使用 JSON-RPC 2.0 作为通信格式。每个请求包含：
 * <ul>
 *   <li>{@code jsonrpc} — 固定 "2.0"</li>
 *   <li>{@code id} — 请求标识符</li>
 *   <li>{@code method} — 方法名（如 "initialize", "tools/list", "tools/call"）</li>
 *   <li>{@code params} — 参数（已序列化的 JSON 字符串）</li>
 * </ul>
 *
 * @param id     请求 ID
 * @param method 方法名
 * @param params 参数 JSON 字符串（可以为 null）
 */
public record JsonRpcRequest(String id, String method, String params) {

    /**
     * 序列化为 JSON 字符串。
     */
    public String toJson() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("jsonrpc", "2.0");
        fields.put("id", id);
        fields.put("method", method);
        if (params != null) {
            fields.put("params", new SimpleJsonParser.RawJson(params));
        }
        return SimpleJsonParser.toJsonObject(fields);
    }

    /**
     * 创建无参数的请求。
     */
    public static JsonRpcRequest of(String id, String method) {
        return new JsonRpcRequest(id, method, null);
    }

    /**
     * 创建带参数的请求。
     */
    public static JsonRpcRequest of(String id, String method, String params) {
        return new JsonRpcRequest(id, method, params);
    }
}
