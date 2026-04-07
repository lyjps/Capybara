package com.co.claudecode.demo.mcp.tool;

import com.co.claudecode.demo.mcp.client.McpConnectionManager;
import com.co.claudecode.demo.mcp.client.McpResourceContent;
import com.co.claudecode.demo.tool.Tool;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolMetadata;
import com.co.claudecode.demo.tool.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * 内建 MCP 工具：读取指定 MCP 资源。
 * <p>
 * 对应 TS 版 ReadMcpResourceTool。
 * 通过 MCP 服务器的 resources/read 接口读取单个资源内容。
 */
public final class ReadMcpResourceTool implements Tool {

    private static final ToolMetadata METADATA = new ToolMetadata(
            "mcp_read_resource",
            "Read a specific resource from an MCP server by URI.",
            true,   // readOnly
            true,   // concurrencySafe
            false,  // destructive
            ToolMetadata.PathDomain.NONE,
            null,
            List.of(
                    new ToolMetadata.ParamInfo("server",
                            "The MCP server name that hosts the resource", true),
                    new ToolMetadata.ParamInfo("uri",
                            "The resource URI to read", true)
            )
    );

    private final McpConnectionManager connectionManager;

    public ReadMcpResourceTool(McpConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public ToolMetadata metadata() {
        return METADATA;
    }

    @Override
    public void validate(Map<String, String> input) {
        if (!input.containsKey("server") || input.get("server").isBlank()) {
            throw new IllegalArgumentException("'server' parameter is required");
        }
        if (!input.containsKey("uri") || input.get("uri").isBlank()) {
            throw new IllegalArgumentException("'uri' parameter is required");
        }
    }

    @Override
    public ToolResult execute(Map<String, String> input, ToolExecutionContext context) throws Exception {
        String server = input.get("server");
        String uri = input.get("uri");

        try {
            McpResourceContent content = connectionManager.readResource(server, uri);

            StringBuilder sb = new StringBuilder();
            sb.append("Resource: ").append(content.uri()).append("\n");
            if (content.mimeType() != null) {
                sb.append("Type: ").append(content.mimeType()).append("\n");
            }
            sb.append("\n");

            if (content.isBinary()) {
                sb.append("[Binary content] ").append(content.text());
            } else {
                sb.append(content.text());
            }

            return new ToolResult(false, sb.toString());
        } catch (Exception e) {
            return new ToolResult(true,
                    "Failed to read resource '" + uri + "' from server '" + server + "': "
                            + e.getMessage());
        }
    }
}
