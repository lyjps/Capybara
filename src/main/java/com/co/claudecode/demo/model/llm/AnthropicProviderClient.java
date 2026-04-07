package com.co.claudecode.demo.model.llm;

import com.co.claudecode.demo.mcp.protocol.SimpleJsonParser;
import com.co.claudecode.demo.message.ToolCallBlock;
import com.co.claudecode.demo.tool.streaming.StreamingToolCallback;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Anthropic Messages API 的真实 HTTP 调用实现。
 * 支持流式（SSE streaming）和非流式两种模式。
 */
public final class AnthropicProviderClient extends AbstractLlmProviderClient {

    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final Duration TIMEOUT = Duration.ofSeconds(300);

    /** Maximum number of retries for rate-limited (429) or transient server errors (529). */
    private static final int MAX_RETRIES = 5;
    /** Base delay between retries (exponential backoff: base * 2^attempt). */
    private static final long RETRY_BASE_DELAY_MS = 5_000;
    /** Maximum delay cap for exponential backoff. */
    private static final long RETRY_MAX_DELAY_MS = 60_000;

    private final HttpClient httpClient;

    public AnthropicProviderClient(ModelRuntimeConfig runtimeConfig) {
        super(runtimeConfig);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public ModelProvider provider() {
        return ModelProvider.ANTHROPIC;
    }

    // ================================================================
    //  非流式（原有逻辑）
    // ================================================================

    @Override
    public LlmResponse generate(LlmRequest request) {
        checkConfigured();
        String requestBody = buildRequestBody(request, false);
        String endpoint = buildEndpoint();
        logRequest(request, endpoint);

        for (int attempt = 0; ; attempt++) {
            try {
                HttpResponse<String> httpResponse = doPost(endpoint, requestBody,
                        HttpResponse.BodyHandlers.ofString());

                if (isRetryable(httpResponse.statusCode()) && attempt < MAX_RETRIES) {
                    long delay = computeRetryDelay(attempt, httpResponse);
                    System.out.println("ANTHROPIC > Rate limited (HTTP " + httpResponse.statusCode()
                            + "), retrying in " + (delay / 1000) + "s (attempt " + (attempt + 1)
                            + "/" + MAX_RETRIES + ")...");
                    Thread.sleep(delay);
                    continue;
                }

                if (httpResponse.statusCode() != 200) {
                    throw new RuntimeException(
                            "Anthropic API returned HTTP " + httpResponse.statusCode() + ": " + httpResponse.body());
                }

                // Validate Content-Type — detect HTML/non-JSON responses early
                String contentType = httpResponse.headers().firstValue("content-type").orElse("");
                if (contentType.contains("text/html")) {
                    String preview = httpResponse.body().length() > 500
                            ? httpResponse.body().substring(0, 500) : httpResponse.body();
                    throw new RuntimeException(
                            "Anthropic API returned HTML instead of JSON (Content-Type: " + contentType
                            + "). The base-url may be pointing to a web frontend, not an API endpoint. "
                            + "Endpoint: " + endpoint + " | Response preview: " + preview);
                }

                return parseResponse(httpResponse.body());

            } catch (IOException e) {
                if (attempt < MAX_RETRIES) {
                    long delay = computeRetryDelay(attempt, null);
                    System.out.println("ANTHROPIC > Network error, retrying in " + (delay / 1000)
                            + "s (attempt " + (attempt + 1) + "/" + MAX_RETRIES + "): " + e.getMessage());
                    try { Thread.sleep(delay); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Anthropic API request interrupted", ie);
                    }
                    continue;
                }
                throw new RuntimeException("Anthropic API network error: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Anthropic API request interrupted", e);
            }
        }
    }

    // ================================================================
    //  流式（SSE Streaming）
    // ================================================================

    @Override
    public LlmResponse generateStream(LlmRequest request, StreamCallback callback) {
        checkConfigured();
        String requestBody = buildRequestBody(request, true);
        String endpoint = buildEndpoint();
        logRequest(request, endpoint);

        for (int attempt = 0; ; attempt++) {
            try {
                HttpResponse<java.io.InputStream> httpResponse = doPost(endpoint, requestBody,
                        HttpResponse.BodyHandlers.ofInputStream());

                if (isRetryable(httpResponse.statusCode()) && attempt < MAX_RETRIES) {
                    // Drain the error body for logging before retry
                    String errorBody = new String(httpResponse.body().readAllBytes(), StandardCharsets.UTF_8);
                    long delay = computeRetryDelay(attempt, httpResponse);
                    System.out.println("ANTHROPIC > Rate limited (HTTP " + httpResponse.statusCode()
                            + "), retrying in " + (delay / 1000) + "s (attempt " + (attempt + 1)
                            + "/" + MAX_RETRIES + "): " + errorBody);
                    Thread.sleep(delay);
                    continue;
                }

                if (httpResponse.statusCode() != 200) {
                    String errorBody = new String(httpResponse.body().readAllBytes(), StandardCharsets.UTF_8);
                    throw new RuntimeException(
                            "Anthropic API returned HTTP " + httpResponse.statusCode() + ": " + errorBody);
                }

                // Validate Content-Type — detect HTML/non-SSE responses early
                String contentType = httpResponse.headers().firstValue("content-type").orElse("");
                if (contentType.contains("text/html")) {
                    String preview = new String(httpResponse.body().readNBytes(500), StandardCharsets.UTF_8);
                    throw new RuntimeException(
                            "Anthropic API returned HTML instead of SSE stream (Content-Type: " + contentType
                            + "). The base-url may be pointing to a web frontend, not an API endpoint. "
                            + "Endpoint: " + endpoint + " | Response preview: " + preview);
                }

                return parseSseStream(httpResponse.body(), callback);

            } catch (IOException e) {
                if (attempt < MAX_RETRIES) {
                    long delay = computeRetryDelay(attempt, null);
                    System.out.println("ANTHROPIC > Network error, retrying in " + (delay / 1000)
                            + "s (attempt " + (attempt + 1) + "/" + MAX_RETRIES + "): " + e.getMessage());
                    try { Thread.sleep(delay); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Anthropic API request interrupted", ie);
                    }
                    continue;
                }
                throw new RuntimeException("Anthropic API network error: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Anthropic API request interrupted", e);
            }
        }
    }

    /**
     * 逐行读取 SSE 流，实时回调文本 token，最终组装完整 LlmResponse。
     *
     * SSE 格式：
     *   event: content_block_start
     *   data: {"content_block":{"type":"text",...}}
     *
     *   event: content_block_delta
     *   data: {"delta":{"type":"text_delta","text":"Hello"}}
     *
     *   event: content_block_delta
     *   data: {"delta":{"type":"input_json_delta","partial_json":"..."}}
     *
     *   event: content_block_stop
     *   data: {}
     *
     *   event: message_stop
     *   data: {}
     */
    private LlmResponse parseSseStream(java.io.InputStream inputStream, StreamCallback callback)
            throws IOException {

        StringBuilder textBuffer = new StringBuilder();
        List<LlmResponse.ToolCallData> toolCalls = new ArrayList<>();

        // 当前 content block 的状态
        String currentBlockType = null;   // "text" or "tool_use"
        String currentToolId = null;
        String currentToolName = null;
        StringBuilder toolInputJsonBuffer = new StringBuilder();

        String currentEvent = null;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {

                if (line.startsWith("event:")) {
                    currentEvent = line.substring(6).trim();
                    continue;
                }

                if (!line.startsWith("data:")) {
                    continue; // 空行或其他，跳过
                }

                String data = line.substring(5).trim();
                if (data.isEmpty() || data.equals("{}")) {
                    continue;
                }

                if ("content_block_start".equals(currentEvent)) {
                    // 检测 block 类型
                    String blockType = extractNestedFieldValue(data, "content_block", "type");
                    if ("text".equals(blockType)) {
                        currentBlockType = "text";
                    } else if ("tool_use".equals(blockType)) {
                        currentBlockType = "tool_use";
                        currentToolId = extractNestedFieldValue(data, "content_block", "id");
                        currentToolName = extractNestedFieldValue(data, "content_block", "name");
                        toolInputJsonBuffer.setLength(0);
                    }

                } else if ("content_block_delta".equals(currentEvent)) {
                    String deltaType = extractNestedFieldValue(data, "delta", "type");

                    if ("text_delta".equals(deltaType)) {
                        String text = extractNestedFieldValue(data, "delta", "text");
                        if (text != null) {
                            textBuffer.append(text);
                            if (callback != null) {
                                callback.onTextToken(text);
                            }
                        }
                    } else if ("input_json_delta".equals(deltaType)) {
                        String partial = extractNestedFieldValue(data, "delta", "partial_json");
                        if (partial != null) {
                            toolInputJsonBuffer.append(partial);
                        }
                    }

                } else if ("content_block_stop".equals(currentEvent)) {
                    if ("tool_use".equals(currentBlockType) && currentToolName != null) {
                        Map<String, String> input = parseSimpleJsonObject(toolInputJsonBuffer.toString());
                        LlmResponse.ToolCallData tcd = new LlmResponse.ToolCallData(
                                currentToolId != null ? currentToolId : "",
                                currentToolName,
                                input
                        );
                        toolCalls.add(tcd);

                        // 流式工具执行：通知回调立即开始执行该工具
                        if (callback instanceof StreamingToolCallback stc) {
                            stc.onToolUseComplete(
                                    new ToolCallBlock(tcd.id(), tcd.name(), tcd.input()));
                        }
                    }
                    currentBlockType = null;
                    currentToolId = null;
                    currentToolName = null;
                    toolInputJsonBuffer.setLength(0);

                } else if ("message_stop".equals(currentEvent)) {
                    break;
                }

                // 重置 event（每对 event/data 只消费一次）
                currentEvent = null;
            }
        }

        return new LlmResponse(textBuffer.toString(), toolCalls);
    }

    /**
     * 从 {"outer":{"key":"value",...}} 结构中提取 outer.key 的值。
     * 简易实现，足以处理 SSE data payload。
     */
    private String extractNestedFieldValue(String json, String outerKey, String innerKey) {
        String marker = "\"" + outerKey + "\"";
        int outerIdx = json.indexOf(marker);
        if (outerIdx == -1) return null;

        // 在 outer 对象范围内找 inner key
        int braceStart = json.indexOf("{", outerIdx + marker.length());
        if (braceStart == -1) return null;

        String innerMarker = "\"" + innerKey + "\"";
        int innerIdx = json.indexOf(innerMarker, braceStart);
        if (innerIdx == -1) return null;

        int colonIdx = json.indexOf(":", innerIdx + innerMarker.length());
        if (colonIdx == -1) return null;

        return extractJsonStringValue(json, colonIdx + 1);
    }

    /**
     * 解析简单的 JSON 对象字符串为 Map。
     * 如 {"path":"src","depth":"3"} -> {path=src, depth=3}
     */
    private Map<String, String> parseSimpleJsonObject(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return result;

        String trimmed = json.trim();
        if (trimmed.startsWith("{")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("}")) trimmed = trimmed.substring(0, trimmed.length() - 1);

        int pos = 0;
        while (pos < trimmed.length()) {
            int keyStart = trimmed.indexOf("\"", pos);
            if (keyStart == -1) break;

            String key = extractJsonStringValue(trimmed, keyStart);
            if (key == null) break;
            pos = skipPastString(trimmed, keyStart);

            int colonIdx = trimmed.indexOf(":", pos);
            if (colonIdx == -1) break;

            int valueStart = colonIdx + 1;
            while (valueStart < trimmed.length() && trimmed.charAt(valueStart) == ' ') valueStart++;

            if (valueStart < trimmed.length() && trimmed.charAt(valueStart) == '"') {
                String value = extractJsonStringValue(trimmed, valueStart);
                if (value != null) result.put(key, value);
                pos = skipPastString(trimmed, valueStart);
            } else {
                int end = trimmed.indexOf(",", valueStart);
                if (end == -1) end = trimmed.length();
                String value = trimmed.substring(valueStart, end).trim();
                if (!value.isEmpty()) result.put(key, value);
                pos = end + 1;
            }
        }
        return result;
    }

    // ================================================================
    //  共用基础设施
    // ================================================================

    private void checkConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "Anthropic provider is not configured. " +
                    "Set anthropic.auth-token in application.properties or ANTHROPIC_AUTH_TOKEN env var.");
        }
    }

    private void logRequest(LlmRequest request, String endpoint) {
        System.out.println("ANTHROPIC > POST " + endpoint);
        System.out.println("ANTHROPIC > model=" + request.modelName()
                + ", messages=" + request.messages().size()
                + ", tools=" + request.tools().size());
    }

    private <T> HttpResponse<T> doPost(String endpoint, String body,
                                        HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("x-api-key", runtimeConfig().apiKey())
                .header("Authorization", "Bearer " + runtimeConfig().apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return httpClient.send(httpRequest, handler);
    }

    // ---- retry helpers ----

    /**
     * Returns {@code true} for HTTP status codes that should trigger a retry:
     * <ul>
     *   <li>429 — rate limited (too many requests)</li>
     *   <li>529 — API overloaded (Anthropic-specific)</li>
     *   <li>500, 502, 503 — transient server errors</li>
     * </ul>
     */
    private static boolean isRetryable(int statusCode) {
        return statusCode == 429 || statusCode == 529
                || statusCode == 500 || statusCode == 502 || statusCode == 503;
    }

    /**
     * Compute retry delay using exponential backoff with jitter.
     * Respects {@code Retry-After} header if present.
     */
    private static long computeRetryDelay(int attempt, HttpResponse<?> response) {
        // Check Retry-After header first (value in seconds)
        if (response != null) {
            String retryAfter = response.headers().firstValue("retry-after").orElse(null);
            if (retryAfter != null) {
                try {
                    long retrySeconds = Long.parseLong(retryAfter.trim());
                    if (retrySeconds > 0 && retrySeconds < 300) {
                        return retrySeconds * 1000;
                    }
                } catch (NumberFormatException ignored) {
                    // Fall through to exponential backoff
                }
            }
        }
        // Exponential backoff: base * 2^attempt, capped, with ±20% jitter
        long delay = Math.min(RETRY_BASE_DELAY_MS * (1L << attempt), RETRY_MAX_DELAY_MS);
        long jitter = (long) (delay * 0.2 * (Math.random() - 0.5)); // ±10% actual
        return delay + jitter;
    }

    // ---- endpoint ----

    /**
     * Build the full API endpoint URL.
     *
     * The base-url is treated as the API root (matching Anthropic SDK convention):
     * - If it already ends with {@code /messages}, use as-is (explicit full path)
     * - Otherwise, append {@code /v1/messages} (standard Anthropic API versioned path)
     *
     * Examples:
     *   "https://api.anthropic.com"                → .../v1/messages
     *   "https://aigc.sankuai.com/v1/anthropic/"    → .../v1/anthropic/v1/messages  (proxy root)
     *   "https://example.com/v1/messages"           → .../v1/messages  (already complete)
     */
    private String buildEndpoint() {
        String baseUrl = runtimeConfig().baseUrl();
        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        if (baseUrl.endsWith("/messages")) return baseUrl;
        return baseUrl + "/v1/messages";
    }

    // ---- request body ----

    private String buildRequestBody(LlmRequest request, boolean stream) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"model\":\"").append(escapeJson(request.modelName())).append("\"");
        sb.append(",\"max_tokens\":").append(runtimeConfig().maxOutputTokens());
        if (stream) {
            sb.append(",\"stream\":true");
        }
        sb.append(",\"system\":\"").append(escapeJson(request.systemPrompt())).append("\"");

        if (!request.tools().isEmpty()) {
            sb.append(",\"tools\":[");
            sb.append(request.tools().stream()
                    .map(this::toolSchemaToJson)
                    .collect(Collectors.joining(",")));
            sb.append("]");
        }

        sb.append(",\"messages\":[");
        sb.append(request.messages().stream()
                .map(this::messageToJson)
                .collect(Collectors.joining(",")));
        sb.append("]");

        sb.append("}");
        return sb.toString();
    }

    private String toolSchemaToJson(LlmRequest.ToolSchema tool) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"name\":\"").append(escapeJson(tool.name())).append("\"");
        sb.append(",\"description\":\"").append(escapeJson(tool.description())).append("\"");
        sb.append(",\"input_schema\":{\"type\":\"object\"");
        if (!tool.parameters().isEmpty()) {
            sb.append(",\"properties\":{");
            sb.append(tool.parameters().stream()
                    .map(p -> "\"" + escapeJson(p.name()) + "\":{\"type\":\"string\",\"description\":\""
                            + escapeJson(p.description()) + "\"}")
                    .collect(Collectors.joining(",")));
            sb.append("}");
            List<String> required = tool.parameters().stream()
                    .filter(LlmRequest.ToolSchema.ParamSchema::required)
                    .map(LlmRequest.ToolSchema.ParamSchema::name)
                    .toList();
            if (!required.isEmpty()) {
                sb.append(",\"required\":[");
                sb.append(required.stream()
                        .map(n -> "\"" + escapeJson(n) + "\"")
                        .collect(Collectors.joining(",")));
                sb.append("]");
            }
        }
        sb.append("}}");
        return sb.toString();
    }

    private String messageToJson(LlmMessage msg) {
        return switch (msg.type()) {
            case TOOL_CALLS -> assistantToolCallToJson(msg);
            case TOOL_RESULT -> toolResultToJson(msg);
            case TEXT -> textMessageToJson(msg);
        };
    }

    private String textMessageToJson(LlmMessage msg) {
        return "{\"role\":\"" + escapeJson(msg.role())
                + "\",\"content\":\"" + escapeJson(msg.content()) + "\"}";
    }

    private String assistantToolCallToJson(LlmMessage msg) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"role\":\"assistant\",\"content\":[");
        List<String> blocks = new ArrayList<>();
        if (msg.content() != null && !msg.content().isBlank()) {
            blocks.add("{\"type\":\"text\",\"text\":\"" + escapeJson(msg.content()) + "\"}");
        }
        for (ToolCallBlock tc : msg.toolCalls()) {
            StringBuilder tcJson = new StringBuilder();
            tcJson.append("{\"type\":\"tool_use\"");
            tcJson.append(",\"id\":\"").append(escapeJson(tc.id())).append("\"");
            tcJson.append(",\"name\":\"").append(escapeJson(tc.toolName())).append("\"");
            tcJson.append(",\"input\":{");
            tcJson.append(tc.input().entrySet().stream()
                    .map(e -> "\"" + escapeJson(e.getKey()) + "\":\"" + escapeJson(e.getValue()) + "\"")
                    .collect(Collectors.joining(",")));
            tcJson.append("}}");
            blocks.add(tcJson.toString());
        }
        sb.append(String.join(",", blocks));
        sb.append("]}");
        return sb.toString();
    }

    private String toolResultToJson(LlmMessage msg) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\"");
        sb.append(",\"tool_use_id\":\"").append(escapeJson(msg.toolUseId())).append("\"");
        if (msg.isError()) sb.append(",\"is_error\":true");
        sb.append(",\"content\":\"").append(escapeJson(msg.content())).append("\"");
        sb.append("}]}");
        return sb.toString();
    }

    // ---- 非流式响应解析（保留原有逻辑） ----

    private LlmResponse parseResponse(String body) {
        StringBuilder textResult = new StringBuilder();
        List<LlmResponse.ToolCallData> toolCalls = new ArrayList<>();
        int searchFrom = 0;
        while (true) {
            int typeIdx = body.indexOf("\"type\"", searchFrom);
            if (typeIdx == -1) break;
            int colonAfterType = body.indexOf(":", typeIdx + 6);
            if (colonAfterType == -1) break;
            String typeValue = extractJsonStringValue(body, colonAfterType + 1);
            if (typeValue == null) { searchFrom = typeIdx + 6; continue; }

            if ("text".equals(typeValue)) {
                int textKeyIdx = body.indexOf("\"text\"", colonAfterType + 1);
                if (textKeyIdx != -1 && textKeyIdx - colonAfterType < 50) {
                    int textColonIdx = body.indexOf(":", textKeyIdx + 6);
                    if (textColonIdx != -1) {
                        String text = extractJsonStringValue(body, textColonIdx + 1);
                        if (text != null) {
                            if (!textResult.isEmpty()) textResult.append("\n");
                            textResult.append(text);
                        }
                    }
                    searchFrom = textKeyIdx + 6;
                } else { searchFrom = colonAfterType + 1; }
            } else if ("tool_use".equals(typeValue)) {
                LlmResponse.ToolCallData tc = parseToolUseBlock(body, colonAfterType);
                if (tc != null) toolCalls.add(tc);
                int nextBrace = findClosingBrace(body, typeIdx);
                searchFrom = nextBrace > 0 ? nextBrace : colonAfterType + 1;
            } else { searchFrom = colonAfterType + 1; }
        }
        if (textResult.isEmpty() && toolCalls.isEmpty()) {
            String fallback = extractFirstTextContent(body);
            if (fallback != null) return new LlmResponse(fallback);
            throw new RuntimeException("Failed to parse Anthropic response. Raw: " +
                    body.substring(0, Math.min(500, body.length())));
        }
        return new LlmResponse(textResult.toString(), toolCalls);
    }

    private LlmResponse.ToolCallData parseToolUseBlock(String body, int startFrom) {
        String id = extractFieldValue(body, "\"id\"", startFrom, startFrom + 300);
        String name = extractFieldValue(body, "\"name\"", startFrom, startFrom + 300);
        if (name == null) return null;
        Map<String, String> input = extractInputObject(body, startFrom, startFrom + 2000);
        return new LlmResponse.ToolCallData(id != null ? id : "", name, input);
    }

    private String extractFieldValue(String body, String fieldKey, int from, int to) {
        int safeEnd = Math.min(to, body.length());
        int keyIdx = body.indexOf(fieldKey, from);
        if (keyIdx == -1 || keyIdx > safeEnd) return null;
        int colonIdx = body.indexOf(":", keyIdx + fieldKey.length());
        if (colonIdx == -1 || colonIdx > safeEnd) return null;
        return extractJsonStringValue(body, colonIdx + 1);
    }

    private Map<String, String> extractInputObject(String body, int from, int to) {
        Map<String, String> result = new LinkedHashMap<>();
        int safeEnd = Math.min(to, body.length());
        int inputKeyIdx = body.indexOf("\"input\"", from);
        if (inputKeyIdx == -1 || inputKeyIdx > safeEnd) return result;
        int braceStart = body.indexOf("{", inputKeyIdx + 7);
        if (braceStart == -1 || braceStart > safeEnd) return result;
        int braceEnd = findClosingBrace(body, braceStart);
        if (braceEnd == -1) return result;
        String inputBlock = body.substring(braceStart + 1, braceEnd);
        int pos = 0;
        while (pos < inputBlock.length()) {
            int keyStart = inputBlock.indexOf("\"", pos);
            if (keyStart == -1) break;
            String key = extractJsonStringValue(inputBlock, keyStart);
            if (key == null) break;
            int afterKey = skipPastString(inputBlock, keyStart);
            int colonIdx = inputBlock.indexOf(":", afterKey);
            if (colonIdx == -1) break;
            int valueStart = colonIdx + 1;
            while (valueStart < inputBlock.length() && inputBlock.charAt(valueStart) == ' ') valueStart++;
            if (valueStart < inputBlock.length() && inputBlock.charAt(valueStart) == '"') {
                String value = extractJsonStringValue(inputBlock, valueStart);
                if (value != null) result.put(key, value);
                pos = skipPastString(inputBlock, valueStart);
            } else {
                int end = inputBlock.indexOf(",", valueStart);
                if (end == -1) end = inputBlock.length();
                String value = inputBlock.substring(valueStart, end).trim();
                if (!value.isEmpty()) result.put(key, value);
                pos = end + 1;
            }
        }
        return result;
    }

    // ---- JSON 工具方法 ----

    private int findClosingBrace(String body, int openBraceIdx) {
        int depth = 0; boolean inString = false; boolean escaped = false;
        for (int i = openBraceIdx; i < body.length(); i++) {
            char c = body.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && inString) { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') depth++;
            if (c == '}') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    private int skipPastString(String s, int quoteStart) {
        boolean escaped = false;
        for (int i = quoteStart + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == '"') return i + 1;
        }
        return s.length();
    }

    private String extractFirstTextContent(String json) {
        String marker = "\"text\"";
        int idx = json.lastIndexOf(marker);
        if (idx == -1) return null;
        int colonIdx = json.indexOf(":", idx + marker.length());
        if (colonIdx == -1) return null;
        return extractJsonStringValue(json, colonIdx + 1);
    }

    private String extractJsonStringValue(String json, int fromIndex) {
        int quoteStart = json.indexOf("\"", fromIndex);
        if (quoteStart == -1) return null;
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = quoteStart + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                switch (c) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '/' -> sb.append('/');
                    case 'u' -> {
                        if (i + 4 < json.length()) {
                            String hex = json.substring(i + 1, i + 5);
                            try { sb.append((char) Integer.parseInt(hex, 16)); i += 4; }
                            catch (NumberFormatException e) { sb.append("\\u").append(hex); i += 4; }
                        }
                    }
                    default -> sb.append('\\').append(c);
                }
                escaped = false;
            } else if (c == '\\') { escaped = true; }
            else if (c == '"') { return sb.toString(); }
            else { sb.append(c); }
        }
        return null;
    }

    /**
     * JSON 字符串转义，委托给 {@link SimpleJsonParser#escapeJson(String)}。
     * 消除与 SimpleJsonParser 的重复实现。
     */
    private static String escapeJson(String value) {
        return SimpleJsonParser.escapeJson(value);
    }
}
