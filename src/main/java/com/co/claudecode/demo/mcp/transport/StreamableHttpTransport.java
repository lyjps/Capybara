package com.co.claudecode.demo.mcp.transport;

import com.co.claudecode.demo.mcp.McpServerConfig;
import com.co.claudecode.demo.mcp.protocol.JsonRpcRequest;
import com.co.claudecode.demo.mcp.protocol.JsonRpcResponse;
import com.co.claudecode.demo.mcp.protocol.SimpleJsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Streamable HTTP 传输层（原 SseTransport）。
 * <p>
 * 每个请求是独立的 HTTP POST，response 中直接返回 JSON-RPC 结果。
 * 也支持 text/event-stream 响应（解析最后一个 data 行）。
 * <p>
 * 实现 MCP 规范（2025-03-26）的 Session Management：
 * <ul>
 *   <li>initialize 响应中提取 {@code Mcp-Session-Id} header</li>
 *   <li>后续所有请求附带 {@code Mcp-Session-Id} header</li>
 *   <li>关闭时清空 session ID</li>
 * </ul>
 * <p>
 * 对应 TS 版 StreamableHTTPClientTransport 的简化版本。
 */
public final class StreamableHttpTransport implements McpTransport {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    /** MCP 规范 session ID header 名（请求中发送用）。 */
    private static final String SESSION_ID_HEADER = "Mcp-Session-Id";
    /** MCP 规范 session ID header 名（响应中读取用，小写匹配）。 */
    private static final String SESSION_ID_HEADER_LOWER = "mcp-session-id";

    private final McpServerConfig config;
    private final Supplier<Map<String, String>> dynamicHeaders;
    private HttpClient httpClient;
    private volatile boolean open;

    /**
     * 服务端在 initialize 响应中返回的 session ID。
     * 一旦设置，后续所有请求都必须附带此 header。
     */
    private volatile String sessionId;

    public StreamableHttpTransport(McpServerConfig config) {
        this(config, null);
    }

    /**
     * @param config         服务器配置
     * @param dynamicHeaders 动态 header 供应器（如 JWT Authorization），每次请求时调用。可为 null。
     */
    public StreamableHttpTransport(McpServerConfig config,
                                   Supplier<Map<String, String>> dynamicHeaders) {
        this.config = config;
        this.dynamicHeaders = dynamicHeaders;
    }

    @Override
    public void start() throws IOException {
        if (config.url() == null || config.url().isBlank()) {
            throw new IOException("Streamable HTTP transport requires a URL");
        }
        httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        open = true;
    }

    @Override
    public JsonRpcResponse sendRequest(JsonRpcRequest request) throws IOException {
        checkOpen();

        String requestJson = request.toJson();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(config.url()))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8));

        applyHeaders(builder);

        try {
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            // MCP Session Management: 提取服务端返回的 session ID
            response.headers().firstValue(SESSION_ID_HEADER_LOWER)
                    .ifPresent(id -> this.sessionId = id);

            if (response.statusCode() != 200 && response.statusCode() != 202) {
                throw new IOException("MCP server returned HTTP " + response.statusCode()
                        + ": " + response.body());
            }

            String body = response.body();
            if (body == null || body.isBlank()) {
                return JsonRpcResponse.success(request.id(), "{}");
            }

            // 检查响应是否为 SSE 格式（text/event-stream）
            String contentType = response.headers().firstValue("content-type").orElse("");
            if (contentType.contains("text/event-stream")) {
                return parseSseResponse(body, request.id());
            }

            // 普通 JSON 响应
            return JsonRpcResponse.parse(body);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted", e);
        }
    }

    @Override
    public void sendNotification(String method, String params) throws IOException {
        checkOpen();

        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        if (params != null) {
            notification.put("params", new SimpleJsonParser.RawJson(params));
        }
        String json = SimpleJsonParser.toJsonObject(notification);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(config.url()))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));

        applyHeaders(builder);

        try {
            HttpResponse<Void> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.discarding());

            // MCP Session Management: 提取服务端返回的 session ID（通知也可能触发）
            response.headers().firstValue(SESSION_ID_HEADER_LOWER)
                    .ifPresent(id -> this.sessionId = id);

            if (response.statusCode() != 200 && response.statusCode() != 202) {
                throw new IOException("MCP server returned HTTP " + response.statusCode()
                        + " for notification");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP notification interrupted", e);
        }
    }

    @Override
    public boolean isOpen() {
        return open && httpClient != null;
    }

    @Override
    public void close() {
        open = false;
        sessionId = null;
        httpClient = null;
    }

    /**
     * 获取当前 session ID（测试用）。
     */
    String getSessionId() {
        return sessionId;
    }

    /**
     * 将静态 headers、动态 headers、session ID 统一注入到请求 builder。
     * 消除 sendRequest/sendNotification 中的重复 header 构建逻辑。
     */
    private void applyHeaders(HttpRequest.Builder builder) {
        // 静态自定义 headers
        for (var entry : config.headers().entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
        // 动态 headers（如 Authorization: Bearer xxx）
        if (dynamicHeaders != null) {
            Map<String, String> dynamic = dynamicHeaders.get();
            if (dynamic != null) {
                for (var entry : dynamic.entrySet()) {
                    builder.header(entry.getKey(), entry.getValue());
                }
            }
        }
        // MCP Session Management: 附带 session ID（如果已从 initialize 响应获取）
        String currentSessionId = sessionId;
        if (currentSessionId != null) {
            builder.header(SESSION_ID_HEADER, currentSessionId);
        }
    }

    private void checkOpen() throws IOException {
        if (!isOpen()) {
            throw new IOException("StreamableHttpTransport is not open");
        }
    }

    /**
     * 解析 SSE 格式的响应体，提取最后一个 data 行的 JSON。
     */
    private JsonRpcResponse parseSseResponse(String body, String requestId) {
        String lastData = null;
        for (String line : body.split("\n")) {
            if (line.startsWith("data:")) {
                lastData = line.substring(5).trim();
            }
        }
        if (lastData != null && !lastData.isEmpty()) {
            return JsonRpcResponse.parse(lastData);
        }
        return JsonRpcResponse.success(requestId, "{}");
    }
}
