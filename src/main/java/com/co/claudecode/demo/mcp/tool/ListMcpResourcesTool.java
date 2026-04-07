package com.co.claudecode.demo.mcp.tool;

import com.co.claudecode.demo.mcp.McpResourceInfo;
import com.co.claudecode.demo.mcp.McpServerConnection;
import com.co.claudecode.demo.mcp.client.McpConnectionManager;
import com.co.claudecode.demo.tool.Tool;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolMetadata;
import com.co.claudecode.demo.tool.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * 内建 MCP 工具：列出所有 MCP 服务器的资源。
 * <p>
 * 对应 TS 版 ListMcpResourcesTool。
 * 遍历所有已连接的 MCP 服务器，调用 resources/list，
 * 返回合并后的资源列表。
 * <p>
 * 支持可选的 {@code server} 参数过滤特定服务器。
 */
public final class ListMcpResourcesTool implements Tool {

    private static final ToolMetadata METADATA = new ToolMetadata(
            "mcp_list_resources",
            "List available resources from MCP servers. "
                    + "Returns resource URIs, names, and descriptions.",
            true,   // readOnly
            true,   // concurrencySafe
            false,  // destructive
            ToolMetadata.PathDomain.NONE,
            null,
            List.of(
                    new ToolMetadata.ParamInfo("server",
                            "Optional: filter by server name", false)
            )
    );

    private final McpConnectionManager connectionManager;

    public ListMcpResourcesTool(McpConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public ToolMetadata metadata() {
        return METADATA;
    }

    @Override
    public ToolResult execute(Map<String, String> input, ToolExecutionContext context) throws Exception {
        String serverFilter = input.get("server");
        StringBuilder sb = new StringBuilder();
        int totalResources = 0;

        for (McpServerConnection conn : connectionManager.allConnections()) {
            if (!conn.isConnected()) continue;
            if (!conn.hasResources()) continue;
            if (serverFilter != null && !serverFilter.isBlank()
                    && !conn.name().equalsIgnoreCase(serverFilter)) {
                continue;
            }

            List<McpResourceInfo> resources = conn.resources();
            if (resources.isEmpty()) continue;

            sb.append("## Server: ").append(conn.name()).append("\n\n");
            for (McpResourceInfo resource : resources) {
                sb.append("- **").append(resource.name() != null ? resource.name() : resource.uri())
                        .append("**\n");
                sb.append("  URI: `").append(resource.uri()).append("`\n");
                if (resource.mimeType() != null) {
                    sb.append("  Type: ").append(resource.mimeType()).append("\n");
                }
                if (resource.description() != null) {
                    sb.append("  ").append(resource.description()).append("\n");
                }
                sb.append("\n");
                totalResources++;
            }
        }

        if (totalResources == 0) {
            return new ToolResult(false,
                    "No resources found. MCP servers may still provide tools even if they have no resources.");
        }

        return new ToolResult(false, sb.toString().trim());
    }
}
