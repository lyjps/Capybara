package com.co.claudecode.demo.mcp.client;

import com.co.claudecode.demo.mcp.McpResourceInfo;
import com.co.claudecode.demo.mcp.McpToolInfo;
import com.co.claudecode.demo.mcp.protocol.JsonRpcError;
import com.co.claudecode.demo.mcp.protocol.JsonRpcRequest;
import com.co.claudecode.demo.mcp.protocol.JsonRpcResponse;
import com.co.claudecode.demo.mcp.protocol.SimpleJsonParser;
import com.co.claudecode.demo.mcp.transport.McpTransport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP 客户端 — 与单个 MCP 服务器通信。
 * <p>
 * 对应 TS 版 {@code connectToServer()} + MCP SDK {@code Client}。
 * 负责 initialize 握手、工具列表获取、工具调用、资源操作。
 * <p>
 * 生命周期：
 * <ol>
 *   <li>{@code new McpClient(transport, name)}</li>
 *   <li>{@link #initialize()} — 握手</li>
 *   <li>{@link #listTools()}, {@link #callTool(String, String)}, etc.</li>
 *   <li>{@link #close()} — 关闭</li>
 * </ol>
 */
public final class McpClient implements AutoCloseable {

    /** 工具调用输出最大字符数。 */
    public static final int MAX_OUTPUT_CHARS = 100_000;

    /** 经典 SSE / stdio 传输使用的旧版协议。 */
    public static final String PROTOCOL_VERSION_2024 = "2024-11-05";
    /** Streamable HTTP 传输使用的新版协议（MCPHub 要求此版本）。 */
    public static final String PROTOCOL_VERSION_2025 = "2025-03-26";

    private static final String CLIENT_NAME = "claude-code-java";
    private static final String CLIENT_VERSION = "1.0.0";

    private final McpTransport transport;
    private final String serverName;
    private final String protocolVersion;
    private final AtomicInteger requestIdCounter = new AtomicInteger(0);
    private McpInitResult initResult;

    public McpClient(McpTransport transport, String serverName) {
        this(transport, serverName, PROTOCOL_VERSION_2024);
    }

    /**
     * @param transport       传输层
     * @param serverName      服务器名称
     * @param protocolVersion MCP 协议版本（{@link #PROTOCOL_VERSION_2024} 或 {@link #PROTOCOL_VERSION_2025}）
     */
    public McpClient(McpTransport transport, String serverName, String protocolVersion) {
        this.transport = transport;
        this.serverName = serverName;
        this.protocolVersion = protocolVersion;
    }

    // ================================================================
    //  连接生命周期
    // ================================================================

    /**
     * 执行 MCP initialize 握手。
     * <ol>
     *   <li>发送 "initialize" 请求，声明客户端能力</li>
     *   <li>解析服务器 capabilities、serverInfo、instructions</li>
     *   <li>发送 "notifications/initialized" 通知</li>
     * </ol>
     *
     * @return 初始化结果
     * @throws IOException 如果握手失败
     */
    public McpInitResult initialize() throws IOException {
        // 构建 initialize 参数
        Map<String, Object> clientInfo = new LinkedHashMap<>();
        clientInfo.put("name", CLIENT_NAME);
        clientInfo.put("version", CLIENT_VERSION);

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("roots", new SimpleJsonParser.RawJson("{}"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", protocolVersion);
        params.put("clientInfo", clientInfo);
        params.put("capabilities", capabilities);

        JsonRpcResponse response = sendAndReceive("initialize",
                SimpleJsonParser.toJsonObject(params));

        if (response.isError()) {
            throw new IOException("MCP initialize failed: " + response.error().message());
        }

        // 解析响应
        String result = response.result();
        String serverInfoJson = SimpleJsonParser.extractField(result, "serverInfo");
        String srvName = serverInfoJson != null
                ? SimpleJsonParser.extractField(serverInfoJson, "name") : null;
        String srvVersion = serverInfoJson != null
                ? SimpleJsonParser.extractField(serverInfoJson, "version") : null;

        String instructions = SimpleJsonParser.extractField(result, "instructions");
        if (instructions != null && instructions.length() > McpInitResult.MAX_INSTRUCTIONS_LENGTH) {
            instructions = instructions.substring(0, McpInitResult.MAX_INSTRUCTIONS_LENGTH);
        }

        // 解析 capabilities
        String capJson = SimpleJsonParser.extractField(result, "capabilities");
        boolean supportsTools = capJson != null && capJson.contains("\"tools\"");
        boolean supportsResources = capJson != null && capJson.contains("\"resources\"");
        boolean supportsPrompts = capJson != null && capJson.contains("\"prompts\"");

        this.initResult = new McpInitResult(
                srvName != null ? srvName : serverName,
                srvVersion,
                instructions,
                supportsTools,
                supportsResources,
                supportsPrompts
        );

        // 发送 initialized 通知
        transport.sendNotification("notifications/initialized", null);

        return initResult;
    }

    // ================================================================
    //  工具操作
    // ================================================================

    /**
     * 获取服务器工具列表。
     *
     * @return 工具信息列表
     * @throws IOException 如果请求失败
     */
    public List<McpToolInfo> listTools() throws IOException {
        JsonRpcResponse response = sendAndReceive("tools/list", "{}");
        if (response.isError()) {
            throw new IOException("tools/list failed: " + response.error().message());
        }

        String toolsJson = SimpleJsonParser.extractField(response.result(), "tools");
        if (toolsJson == null || !toolsJson.trim().startsWith("[")) {
            return List.of();
        }

        List<String> toolElements = SimpleJsonParser.parseArrayRaw(toolsJson);
        List<McpToolInfo> tools = new ArrayList<>();

        for (String toolJson : toolElements) {
            String name = SimpleJsonParser.extractField(toolJson, "name");
            String description = SimpleJsonParser.extractField(toolJson, "description");
            String inputSchema = SimpleJsonParser.extractField(toolJson, "inputSchema");

            // 从 inputSchema 解析参数
            List<McpToolInfo.McpParamInfo> params = parseInputSchema(inputSchema);

            // 从 annotations 解析 readOnly
            String annotations = SimpleJsonParser.extractField(toolJson, "annotations");
            boolean readOnly = false;
            if (annotations != null) {
                String readOnlyHint = SimpleJsonParser.extractField(annotations, "readOnlyHint");
                readOnly = "true".equals(readOnlyHint);
            }

            if (name != null) {
                tools.add(new McpToolInfo(name, description, inputSchema, params, readOnly));
            }
        }

        return tools;
    }

    /**
     * 调用服务器上的工具。
     *
     * @param toolName 工具名称（原始名，不含 mcp__ 前缀）
     * @param argsJson 参数 JSON 字符串
     * @return 工具调用结果文本
     * @throws IOException 如果调用失败
     */
    public String callTool(String toolName, String argsJson) throws IOException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        if (argsJson != null && !argsJson.isBlank()) {
            params.put("arguments", new SimpleJsonParser.RawJson(argsJson));
        } else {
            params.put("arguments", new SimpleJsonParser.RawJson("{}"));
        }

        JsonRpcResponse response = sendAndReceive("tools/call",
                SimpleJsonParser.toJsonObject(params));

        if (response.isError()) {
            throw new IOException("tools/call(" + toolName + ") failed: "
                    + response.error().message());
        }

        // 解析 result.content 数组
        String result = response.result();
        String isErrorStr = SimpleJsonParser.extractField(result, "isError");
        boolean isError = "true".equals(isErrorStr);

        String contentJson = SimpleJsonParser.extractField(result, "content");
        String text = extractContentText(contentJson);

        // 截断输出
        if (text != null && text.length() > MAX_OUTPUT_CHARS) {
            text = text.substring(0, MAX_OUTPUT_CHARS) + "\n[Output truncated at "
                    + MAX_OUTPUT_CHARS + " chars]";
        }

        if (isError) {
            throw new IOException("Tool returned error: " + (text != null ? text : "unknown"));
        }

        return text != null ? text : "";
    }

    // ================================================================
    //  资源操作
    // ================================================================

    /**
     * 获取服务器资源列表。
     *
     * @return 资源信息列表
     * @throws IOException 如果请求失败
     */
    public List<McpResourceInfo> listResources() throws IOException {
        JsonRpcResponse response = sendAndReceive("resources/list", "{}");
        if (response.isError()) {
            throw new IOException("resources/list failed: " + response.error().message());
        }

        String resourcesJson = SimpleJsonParser.extractField(response.result(), "resources");
        if (resourcesJson == null || !resourcesJson.trim().startsWith("[")) {
            return List.of();
        }

        List<String> elements = SimpleJsonParser.parseArrayRaw(resourcesJson);
        List<McpResourceInfo> resources = new ArrayList<>();

        for (String elem : elements) {
            String uri = SimpleJsonParser.extractField(elem, "uri");
            String name = SimpleJsonParser.extractField(elem, "name");
            String mimeType = SimpleJsonParser.extractField(elem, "mimeType");
            String description = SimpleJsonParser.extractField(elem, "description");
            if (uri != null) {
                resources.add(new McpResourceInfo(uri, name, mimeType, description));
            }
        }

        return resources;
    }

    /**
     * 读取指定资源。
     *
     * @param uri 资源 URI
     * @return 资源内容
     * @throws IOException 如果读取失败
     */
    public McpResourceContent readResource(String uri) throws IOException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("uri", uri);

        JsonRpcResponse response = sendAndReceive("resources/read",
                SimpleJsonParser.toJsonObject(params));

        if (response.isError()) {
            throw new IOException("resources/read(" + uri + ") failed: "
                    + response.error().message());
        }

        String contentsJson = SimpleJsonParser.extractField(response.result(), "contents");
        if (contentsJson == null || !contentsJson.trim().startsWith("[")) {
            return new McpResourceContent(uri, null, "", false);
        }

        List<String> elements = SimpleJsonParser.parseArrayRaw(contentsJson);
        if (elements.isEmpty()) {
            return new McpResourceContent(uri, null, "", false);
        }

        // 取第一个内容项
        String first = elements.get(0);
        String contentUri = SimpleJsonParser.extractField(first, "uri");
        String mimeType = SimpleJsonParser.extractField(first, "mimeType");
        String text = SimpleJsonParser.extractField(first, "text");
        String blob = SimpleJsonParser.extractField(first, "blob");

        if (blob != null && !blob.isEmpty()) {
            // 二进制内容 — 在 demo 中只标记不保存
            return new McpResourceContent(
                    contentUri != null ? contentUri : uri,
                    mimeType,
                    "[Binary content, " + blob.length() + " base64 chars]",
                    true
            );
        }

        return new McpResourceContent(
                contentUri != null ? contentUri : uri,
                mimeType,
                text != null ? text : "",
                false
        );
    }

    // ================================================================
    //  生命周期
    // ================================================================

    @Override
    public void close() throws IOException {
        transport.close();
    }

    public boolean isOpen() {
        return transport.isOpen();
    }

    public McpInitResult getInitResult() {
        return initResult;
    }

    // ================================================================
    //  内部方法
    // ================================================================

    private JsonRpcResponse sendAndReceive(String method, String params) throws IOException {
        String id = String.valueOf(requestIdCounter.incrementAndGet());
        JsonRpcRequest request = JsonRpcRequest.of(id, method, params);
        return transport.sendRequest(request);
    }

    /**
     * 从 inputSchema JSON 中解析参数列表。
     */
    private List<McpToolInfo.McpParamInfo> parseInputSchema(String inputSchema) {
        if (inputSchema == null || inputSchema.isBlank()) {
            return List.of();
        }

        String propertiesJson = SimpleJsonParser.extractField(inputSchema, "properties");
        if (propertiesJson == null || !propertiesJson.trim().startsWith("{")) {
            return List.of();
        }

        // 解析 required 数组
        String requiredJson = SimpleJsonParser.extractField(inputSchema, "required");
        List<String> required = new ArrayList<>();
        if (requiredJson != null && requiredJson.trim().startsWith("[")) {
            List<String> reqElements = SimpleJsonParser.parseArrayRaw(requiredJson);
            for (String elem : reqElements) {
                // 去除引号
                String cleaned = elem.trim();
                if (cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
                    cleaned = cleaned.substring(1, cleaned.length() - 1);
                }
                required.add(cleaned);
            }
        }

        // 解析各参数
        Map<String, String> properties = SimpleJsonParser.parseFlat(propertiesJson);
        List<McpToolInfo.McpParamInfo> params = new ArrayList<>();

        for (var entry : properties.entrySet()) {
            String paramName = entry.getKey();
            String paramJson = entry.getValue();

            String type = SimpleJsonParser.extractField(paramJson, "type");
            String description = SimpleJsonParser.extractField(paramJson, "description");
            boolean isRequired = required.contains(paramName);

            params.add(new McpToolInfo.McpParamInfo(
                    paramName,
                    type != null ? type : "string",
                    description != null ? description : "",
                    isRequired
            ));
        }

        return params;
    }

    /**
     * 从 content 数组 JSON 中提取文本。
     * 合并所有 text 类型 content block 的文本。
     */
    private String extractContentText(String contentJson) {
        if (contentJson == null || !contentJson.trim().startsWith("[")) {
            return contentJson;
        }

        List<String> elements = SimpleJsonParser.parseArrayRaw(contentJson);
        StringBuilder sb = new StringBuilder();

        for (String elem : elements) {
            String type = SimpleJsonParser.extractField(elem, "type");
            if ("text".equals(type)) {
                String text = SimpleJsonParser.extractField(elem, "text");
                if (text != null) {
                    if (!sb.isEmpty()) sb.append("\n");
                    sb.append(text);
                }
            }
        }

        return sb.isEmpty() ? null : sb.toString();
    }
}
