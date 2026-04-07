package com.co.claudecode.demo.mcp.tool;

import com.co.claudecode.demo.mcp.client.McpConnectionManager;
import com.co.claudecode.demo.mcp.protocol.SimpleJsonParser;
import com.co.claudecode.demo.tool.Tool;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolMetadata;
import com.co.claudecode.demo.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 带名称映射的 MCP 工具适配器。
 * <p>
 * 与 {@link McpToolBridge} 不同，本类支持：
 * <ul>
 *   <li>工具名称映射（暴露名 → 上游名）</li>
 *   <li>模板解析（{@code {mode}} → 从参数中取值替换）</li>
 *   <li>系统参数自动注入（如 lat, lng, userId）</li>
 *   <li>参数删除（转发前移除指定参数）</li>
 * </ul>
 * <p>
 * 用于将美团搜索、美团地图等工具从上游 MCP 服务器映射为对 LLM 友好的工具名。
 */
public final class MappedMcpTool implements Tool {

    private final String serverName;
    private final ToolMapping mapping;
    private final ToolMetadata metadata;
    private final McpConnectionManager connectionManager;

    /**
     * @param serverName        MCP 服务器名（如 "xt-search"、"mt-map"）
     * @param mapping           工具映射规则
     * @param metadata          工具元数据（描述、参数列表）
     * @param connectionManager MCP 连接管理器
     */
    public MappedMcpTool(String serverName, ToolMapping mapping,
                          ToolMetadata metadata, McpConnectionManager connectionManager) {
        this.serverName = serverName;
        this.mapping = mapping;
        this.metadata = metadata;
        this.connectionManager = connectionManager;
    }

    @Override
    public ToolMetadata metadata() {
        return metadata;
    }

    @Override
    public ToolResult execute(Map<String, String> input, ToolExecutionContext context) throws Exception {
        try {
            // 1. 构建可修改的参数副本
            Map<String, Object> forwardParams = new LinkedHashMap<>();
            if (input != null) {
                forwardParams.putAll(input);
            }

            // 2. 解析上游工具名（模板映射需要从参数中取值）
            String upstreamToolName = mapping.resolveUpstreamName(input);

            // 3. 删除不需要转发的参数
            for (String key : mapping.stripParams()) {
                forwardParams.remove(key);
            }

            // 4. 注入系统参数
            for (var entry : mapping.injectParams().entrySet()) {
                forwardParams.put(entry.getKey(), entry.getValue());
            }

            // 5. 序列化参数为 JSON
            String argsJson = SimpleJsonParser.toJsonObject(forwardParams);

            // 6. 调用上游 MCP 工具
            String result = connectionManager.callTool(serverName, upstreamToolName, argsJson);
            return new ToolResult(false, result);

        } catch (Exception e) {
            return new ToolResult(true,
                    "Tool error (" + mapping.exposedName() + " → " + serverName + "): "
                            + e.getMessage());
        }
    }

    /**
     * 获取映射规则（测试用）。
     */
    public ToolMapping mapping() {
        return mapping;
    }

    /**
     * 获取服务器名（测试用）。
     */
    public String serverName() {
        return serverName;
    }
}
