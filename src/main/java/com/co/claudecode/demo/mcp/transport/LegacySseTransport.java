package com.co.claudecode.demo.mcp.transport;

import com.co.claudecode.demo.mcp.McpServerConfig;
import com.co.claudecode.demo.mcp.protocol.JsonRpcRequest;
import com.co.claudecode.demo.mcp.protocol.JsonRpcResponse;
import com.co.claudecode.demo.mcp.protocol.SimpleJsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 经典 MCP SSE 双通道传输层。
 * <p>
 * 实现标准 MCP SSE 协议（Server-Sent Events）：
 * <ol>
 *   <li>GET /sse — 建立持久 SSE 流，接收 {@code event: endpoint\ndata: /message?sessionId=xxx}</li>
 *   <li>POST /message?sessionId=xxx — 发送 JSON-RPC 请求</li>
 *   <li>响应通过 SSE 流中的 {@code data:} 行返回，按 JSON-RPC id 匹配</li>
 * </ol>
 * <p>
 * 用于 xt-search（美团搜索 MCP）和 amap（高德地图 MCP）等经典 SSE 服务器。
 */
public final class LegacySseTransport implements McpTransport {

    /** 等待 endpoint 事件的超时（内网服务可能较慢）。 */
    private static final Duration ENDPOINT_TIMEOUT = Duration.ofSeconds(60);

    /** 等待 JSON-RPC 响应的超时。 */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(60);

    /** POST 请求超时。 */
    private static final Duration POST_TIMEOUT = Duration.ofSeconds(30);

    private final McpServerConfig config;
    private HttpClient httpClient;
    private volatile String postEndpointUrl;
    private volatile boolean open;

    /** 挂起的请求：requestId → CompletableFuture。 */
    private final ConcurrentHashMap<String, CompletableFuture<JsonRpcResponse>> pendingRequests
            = new ConcurrentHashMap<>();

    /** SSE 流读取线程。 */
    private Thread sseReaderThread;

    /** SSE 连接（用于关闭）。 */
    private volatile HttpURLConnection sseConnection;

    /** SSE HTTP 连接是否已建立（用于诊断超时原因）。 */
    private volatile boolean sseConnected;

    public LegacySseTransport(McpServerConfig config) {
        this.config = config;
    }

    @Override
    public void start() throws IOException {
        if (config.url() == null || config.url().isBlank()) {
            throw new IOException("SSE transport requires a URL");
        }

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // 用 CompletableFuture 等待 endpoint 事件
        CompletableFuture<String> endpointFuture = new CompletableFuture<>();

        // 必须在启动读取线程之前设置 open = true，否则 readSseStream 中的
        // while 循环 `&& open` 条件会因 open == false 而立即退出，
        // 导致 endpointFuture 永远不会被 complete，形成死锁超时。
        // 如果后续 endpointFuture.get() 超时/失败，close() 会将 open 设回 false。
        open = true;

        // 启动 SSE 读取线程
        sseReaderThread = new Thread(() -> readSseStream(endpointFuture),
                "sse-reader-" + config.name());
        sseReaderThread.setDaemon(true);
        sseReaderThread.start();

        // 等待 endpoint 事件
        try {
            postEndpointUrl = endpointFuture.get(ENDPOINT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            boolean wasConnected = sseConnected;
            close();
            if (wasConnected) {
                throw new IOException("SSE connection established to " + config.url()
                        + " but no 'event: endpoint' received within " + ENDPOINT_TIMEOUT.toSeconds()
                        + "s. The server may not implement the MCP SSE protocol correctly.");
            } else {
                throw new IOException("Timed out (" + ENDPOINT_TIMEOUT.toSeconds()
                        + "s) connecting to SSE server at " + config.url()
                        + ". Possible causes: (1) Server not reachable (check network/VPN), "
                        + "(2) Server not running, (3) Firewall blocking connection.");
            }
        } catch (Exception e) {
            close();
            throw new IOException("Failed to establish SSE connection to " + config.url()
                    + ": " + e.getMessage(), e);
        }
    }

    @Override
    public JsonRpcResponse sendRequest(JsonRpcRequest request) throws IOException {
        checkOpen();

        CompletableFuture<JsonRpcResponse> future = new CompletableFuture<>();
        pendingRequests.put(request.id(), future);

        try {
            // POST JSON-RPC 请求到 /message?sessionId=xxx
            postJsonRpc(request.toJson());

            // 等待 SSE 流中的响应
            return future.get(RESPONSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        } catch (TimeoutException e) {
            pendingRequests.remove(request.id());
            throw new IOException("Timed out waiting for response to request " + request.id());
        } catch (Exception e) {
            pendingRequests.remove(request.id());
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Request failed: " + e.getMessage(), e);
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

        postJsonRpc(json);
    }

    @Override
    public boolean isOpen() {
        return open && postEndpointUrl != null;
    }

    @Override
    public void close() {
        open = false;

        // 关闭 SSE 连接
        HttpURLConnection conn = sseConnection;
        if (conn != null) {
            try {
                conn.disconnect();
            } catch (Exception ignored) {
            }
            sseConnection = null;
        }

        // 中断读取线程
        if (sseReaderThread != null) {
            sseReaderThread.interrupt();
            sseReaderThread = null;
        }

        // 取消所有挂起的请求
        for (var entry : pendingRequests.entrySet()) {
            entry.getValue().completeExceptionally(
                    new IOException("Transport closed"));
        }
        pendingRequests.clear();

        httpClient = null;
        postEndpointUrl = null;
    }

    /**
     * 获取当前 POST 端点 URL（测试用）。
     */
    String getPostEndpointUrl() {
        return postEndpointUrl;
    }

    // ================================================================
    //  SSE Stream Reader
    // ================================================================

    private void readSseStream(CompletableFuture<String> endpointFuture) {
        try {
            URL url = URI.create(config.url()).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            sseConnection = conn;

            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setDoInput(true);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(0); // 无超时，持久连接

            // 添加自定义 headers
            for (var entry : config.headers().entrySet()) {
                conn.setRequestProperty(entry.getKey(), entry.getValue());
            }

            conn.connect();

            int statusCode = conn.getResponseCode();
            if (statusCode != 200) {
                String errorDetail = "";
                try {
                    var errorStream = conn.getErrorStream();
                    if (errorStream != null) {
                        errorDetail = " — " + new String(errorStream.readAllBytes(), StandardCharsets.UTF_8);
                    }
                } catch (Exception ignored) {
                }
                endpointFuture.completeExceptionally(
                        new IOException("SSE server returned HTTP " + statusCode + errorDetail));
                return;
            }

            // 连接成功，等待 endpoint 事件
            sseConnected = true;

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {

                String currentEvent = null;
                StringBuilder dataBuilder = new StringBuilder();

                String line;
                while ((line = reader.readLine()) != null && open) {
                    if (line.startsWith("event:")) {
                        currentEvent = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        if (dataBuilder.length() > 0) dataBuilder.append('\n');
                        dataBuilder.append(line.substring(5).trim());
                    } else if (line.isEmpty()) {
                        // 空行 = 事件结束
                        String data = dataBuilder.toString();
                        dataBuilder.setLength(0);

                        if ("endpoint".equals(currentEvent)) {
                            // endpoint 事件：解析 POST URL
                            String resolvedUrl = resolvePostUrl(config.url(), data);
                            endpointFuture.complete(resolvedUrl);
                        } else if (data.startsWith("{")) {
                            // 默认 message 事件：JSON-RPC 响应
                            dispatchResponse(data);
                        }

                        currentEvent = null;
                    }
                }
            }
        } catch (Exception e) {
            if (open) {
                endpointFuture.completeExceptionally(e);
                // 标记所有挂起请求失败
                for (var entry : pendingRequests.entrySet()) {
                    entry.getValue().completeExceptionally(
                            new IOException("SSE stream error: " + e.getMessage()));
                }
            }
        }
    }

    /**
     * 分发 SSE 流中收到的 JSON-RPC 响应。
     */
    private void dispatchResponse(String json) {
        try {
            // 提取 id 字段
            String id = SimpleJsonParser.extractField(json, "id");
            if (id == null) return; // 通知或无 id 的消息，忽略

            CompletableFuture<JsonRpcResponse> future = pendingRequests.remove(id);
            if (future != null) {
                future.complete(JsonRpcResponse.parse(json));
            }
        } catch (Exception e) {
            // 解析失败，忽略这条消息
        }
    }

    // ================================================================
    //  POST Helper
    // ================================================================

    private void postJsonRpc(String json) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(postEndpointUrl))
                .timeout(POST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));

        // 添加自定义 headers
        for (var entry : config.headers().entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }

        try {
            HttpResponse<Void> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.discarding());

            // POST 响应体被丢弃 — 真正的响应通过 SSE 流返回
            if (response.statusCode() != 200 && response.statusCode() != 202) {
                throw new IOException("POST to SSE endpoint returned HTTP " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("POST interrupted", e);
        }
    }

    private void checkOpen() throws IOException {
        if (!isOpen()) {
            throw new IOException("LegacySseTransport is not open");
        }
    }

    // ================================================================
    //  URL Resolution
    // ================================================================

    /**
     * 根据 SSE 基础 URL 和 endpoint 事件 data 解析出完整的 POST URL。
     * <p>
     * 例如：base = "http://10.231.105.187:8080/sse"，data = "/message?sessionId=xxx"
     * → "http://10.231.105.187:8080/message?sessionId=xxx"
     */
    static String resolvePostUrl(String baseUrl, String endpointData) {
        try {
            URI base = URI.create(baseUrl);
            URI resolved = base.resolve(endpointData);

            // 安全检查：origin 必须匹配
            String baseOrigin = base.getScheme() + "://" + base.getAuthority();
            String resolvedOrigin = resolved.getScheme() + "://" + resolved.getAuthority();
            if (!baseOrigin.equals(resolvedOrigin)) {
                throw new IllegalArgumentException(
                        "Endpoint origin mismatch: " + resolvedOrigin + " vs " + baseOrigin);
            }

            return resolved.toString();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to resolve POST URL from base=" + baseUrl + ", data=" + endpointData, e);
        }
    }
}
