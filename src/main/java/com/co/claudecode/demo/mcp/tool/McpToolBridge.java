package com.co.claudecode.demo.mcp.tool;

import com.co.claudecode.demo.mcp.McpNameUtils;
import com.co.claudecode.demo.mcp.McpToolInfo;
import com.co.claudecode.demo.mcp.client.McpConnectionManager;
import com.co.claudecode.demo.mcp.protocol.SimpleJsonParser;
import com.co.claudecode.demo.tool.Tool;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolMetadata;
import com.co.claudecode.demo.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具桥接 — 将 MCP 服务器工具适配为 Java {@link Tool} 接口。
 * <p>
 * 对应 TS 版 client.ts 中 fetchToolsForClient() 的 object spread 模式。
 * 每个 MCP 服务器上的工具都被包装成一个实现 {@link Tool} 接口的 Java 对象，
 * 其 {@code execute()} 方法通过 {@link McpConnectionManager} 路由到对应的 MCP 服务器。
 * <p>
 * 工具命名遵循 {@code mcp__<serverName>__<toolName>} 约定。
 */
public final class McpToolBridge implements Tool {

    /** 工具描述最大长度（与 TS MAX_MCP_DESCRIPTION_LENGTH 一致）。 */
    private static final int MAX_DESCRIPTION_LENGTH = 2048;

    private final String serverName;
    private final McpToolInfo toolInfo;
    private final McpConnectionManager connectionManager;
    private final ToolMetadata metadata;

    public McpToolBridge(String serverName, McpToolInfo toolInfo,
                         McpConnectionManager connectionManager) {
        this.serverName = serverName;
        this.toolInfo = toolInfo;
        this.connectionManager = connectionManager;

        // 构建 ToolMetadata
        String fullName = McpNameUtils.buildToolName(serverName, toolInfo.name());
        String description = toolInfo.description() != null ? toolInfo.description() : "";
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            description = description.substring(0, MAX_DESCRIPTION_LENGTH);
        }

        // 转换参数列表
        List<ToolMetadata.ParamInfo> params = toolInfo.params().stream()
                .map(p -> new ToolMetadata.ParamInfo(
                        p.name(),
                        p.description() + (p.type() != null ? " (type: " + p.type() + ")" : ""),
                        p.required()
                ))
                .toList();

        this.metadata = new ToolMetadata(
                fullName,
                description,
                toolInfo.readOnly(),          // readOnly
                toolInfo.readOnly(),          // concurrencySafe（只读工具是并发安全的）
                false,                        // destructive
                ToolMetadata.PathDomain.NONE, // MCP 工具不操作本地路径
                null,                         // pathInputKey
                params,
                true,                         // isMcp — MCP 工具默认延迟加载
                false,                        // shouldDefer
                false,                        // alwaysLoad
                ""                            // searchHint
        );
    }

    @Override
    public ToolMetadata metadata() {
        return metadata;
    }

    @Override
    public ToolResult execute(Map<String, String> input, ToolExecutionContext context) throws Exception {
        try {
            // 将 Map<String, String> 序列化为 JSON 参数
            String argsJson = mapToJson(input);

            // 通过连接管理器路由到 MCP 服务器
            String normalizedServer = McpNameUtils.normalizeForMcp(serverName);
            String result = connectionManager.callTool(normalizedServer, toolInfo.name(), argsJson);

            return new ToolResult(false, result);
        } catch (Exception e) {
            return new ToolResult(true,
                    "MCP tool error (" + serverName + "/" + toolInfo.name() + "): " + e.getMessage());
        }
    }

    /**
     * 获取原始 MCP 服务器名。
     */
    public String serverName() {
        return serverName;
    }

    /**
     * 获取原始 MCP 工具信息。
     */
    public McpToolInfo toolInfo() {
        return toolInfo;
    }

    // ================================================================
    //  工厂方法
    // ================================================================

    /**
     * 从连接管理器中所有已连接的服务器创建工具桥接。
     *
     * @param mgr 连接管理器
     * @return 所有 MCP 工具的 Tool 适配器列表
     */
    public static List<Tool> createAll(McpConnectionManager mgr) {
        List<Tool> tools = new ArrayList<>();
        for (var conn : mgr.allConnections()) {
            if (conn.isConnected()) {
                for (McpToolInfo toolInfo : conn.tools()) {
                    tools.add(new McpToolBridge(conn.name(), toolInfo, mgr));
                }
            }
        }
        return tools;
    }

    /**
     * 为指定服务器创建工具桥接。
     * <p>
     * 仅创建该服务器的工具，跳过其他服务器。适用于需要对不同服务器
     * 使用不同桥接策略的场景（如 xt-search/mt-map 使用 {@link MappedMcpTool}，
     * amap 使用直通 {@link McpToolBridge}）。
     *
     * @param mgr        连接管理器
     * @param serverName 服务器名
     * @return 该服务器的 Tool 适配器列表
     */
    public static List<Tool> createForServer(McpConnectionManager mgr, String serverName) {
        var conn = mgr.getConnection(serverName);
        if (conn == null || !conn.isConnected()) return List.of();
        List<Tool> tools = new ArrayList<>();
        for (McpToolInfo toolInfo : conn.tools()) {
            tools.add(new McpToolBridge(conn.name(), toolInfo, mgr));
        }
        return tools;
    }

    /**
     * 为指定服务器集合创建工具桥接。跳过不在集合中的服务器。
     *
     * @param mgr         连接管理器
     * @param serverNames 要创建桥接的服务器名集合
     * @return Tool 适配器列表
     */
    public static List<Tool> createForServers(McpConnectionManager mgr, java.util.Set<String> serverNames) {
        List<Tool> tools = new ArrayList<>();
        for (var conn : mgr.allConnections()) {
            if (conn.isConnected() && serverNames.contains(conn.name())) {
                for (McpToolInfo toolInfo : conn.tools()) {
                    tools.add(new McpToolBridge(conn.name(), toolInfo, mgr));
                }
            }
        }
        return tools;
    }

    // ================================================================
    //  内部方法
    // ================================================================

    /**
     * 将 Map 转为 JSON 对象字符串。
     * 所有值作为字符串处理（MCP 工具的参数在 Tool 接口层面是 Map&lt;String, String&gt;）。
     */
    private String mapToJson(Map<String, String> input) {
        if (input == null || input.isEmpty()) return "{}";
        Map<String, Object> params = new LinkedHashMap<>(input);
        return SimpleJsonParser.toJsonObject(params);
    }
}
