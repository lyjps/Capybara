package com.co.claudecode.demo.mcp.protocol;

/**
 * JSON-RPC 2.0 响应消息。
 * <p>
 * 成功时 {@code result} 非 null，{@code error} 为 null。
 * 失败时 {@code error} 非 null，{@code result} 为 null。
 *
 * @param id     请求 ID（与对应的 request.id 匹配）
 * @param result 成功结果（原始 JSON 字符串）
 * @param error  错误对象
 */
public record JsonRpcResponse(String id, String result, JsonRpcError error) {

    /**
     * 是否为错误响应。
     */
    public boolean isError() {
        return error != null;
    }

    /**
     * 从原始 JSON 字符串解析响应。
     */
    public static JsonRpcResponse parse(String json) {
        if (json == null || json.isBlank()) {
            return new JsonRpcResponse(null, null,
                    new JsonRpcError(JsonRpcError.PARSE_ERROR, "Empty response", null));
        }

        String id = SimpleJsonParser.extractField(json, "id");

        // 检查是否有 error 字段
        String errorJson = SimpleJsonParser.extractField(json, "error");
        if (errorJson != null && errorJson.trim().startsWith("{")) {
            String codeStr = SimpleJsonParser.extractField(errorJson, "code");
            String message = SimpleJsonParser.extractField(errorJson, "message");
            String data = SimpleJsonParser.extractField(errorJson, "data");
            int code = JsonRpcError.INTERNAL_ERROR;
            if (codeStr != null) {
                try {
                    code = Integer.parseInt(codeStr.trim());
                } catch (NumberFormatException ignored) {
                }
            }
            return new JsonRpcResponse(id, null, new JsonRpcError(code, message, data));
        }

        // 提取 result 字段
        String result = SimpleJsonParser.extractField(json, "result");
        return new JsonRpcResponse(id, result, null);
    }

    /**
     * 创建成功响应。
     */
    public static JsonRpcResponse success(String id, String result) {
        return new JsonRpcResponse(id, result, null);
    }

    /**
     * 创建错误响应。
     */
    public static JsonRpcResponse error(String id, JsonRpcError error) {
        return new JsonRpcResponse(id, null, error);
    }
}
