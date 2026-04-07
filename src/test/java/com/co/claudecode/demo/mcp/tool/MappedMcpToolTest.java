package com.co.claudecode.demo.mcp.tool;

import com.co.claudecode.demo.mcp.McpConnectionState;
import com.co.claudecode.demo.mcp.McpServerConfig;
import com.co.claudecode.demo.mcp.McpServerConnection;
import com.co.claudecode.demo.mcp.client.McpClient;
import com.co.claudecode.demo.mcp.client.McpConnectionManager;
import com.co.claudecode.demo.mcp.client.McpInitResult;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolMetadata;
import com.co.claudecode.demo.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MappedMcpTool 单元测试。
 */
class MappedMcpToolTest {

    private static final ToolExecutionContext CONTEXT = new ToolExecutionContext(
            Path.of("/tmp"), Path.of("/tmp/output"));

    // ---- 元数据测试 ----

    @Test
    void metadata_returnsCorrectName() {
        MappedMcpTool tool = createTool("meituan_search_mix",
                ToolMapping.literal("meituan_search_mix", "offline_meituan_search_mix"));
        assertEquals("meituan_search_mix", tool.metadata().name());
    }

    @Test
    void metadata_returnsDescription() {
        MappedMcpTool tool = createTool("test_tool",
                ToolMapping.literal("test_tool", "upstream_test"));
        assertNotNull(tool.metadata().description());
        assertFalse(tool.metadata().description().isEmpty());
    }

    @Test
    void metadata_readOnly() {
        MappedMcpTool tool = createTool("test", ToolMapping.literal("test", "upstream"));
        assertTrue(tool.metadata().readOnly());
    }

    // ---- mapping 和 serverName 访问 ----

    @Test
    void mapping_returnsCorrectMapping() {
        ToolMapping mapping = ToolMapping.literal("exposed", "upstream");
        MappedMcpTool tool = createTool("exposed", mapping);
        assertSame(mapping, tool.mapping());
    }

    @Test
    void serverName_returnsCorrect() {
        MappedMcpTool tool = createToolWithServer("my-server", "exposed",
                ToolMapping.literal("exposed", "upstream"));
        assertEquals("my-server", tool.serverName());
    }

    // ---- execute 错误处理 ----

    @Test
    void execute_mcpServerNotConnected_returnsError() throws Exception {
        McpConnectionManager mgr = new McpConnectionManager();
        MappedMcpTool tool = new MappedMcpTool(
                "nonexistent-server",
                ToolMapping.literal("test", "upstream_test"),
                createMetadata("test"),
                mgr);

        ToolResult result = tool.execute(Map.of("key", "value"), CONTEXT);
        assertTrue(result.error());
        assertTrue(result.content().contains("nonexistent-server"));
        mgr.close();
    }

    @Test
    void execute_templateMapping_producesCorrectName() throws Exception {
        // 验证模板映射不会抛异常（实际调用会失败因为没有真实服务器）
        McpConnectionManager mgr = new McpConnectionManager();
        MappedMcpTool tool = new MappedMcpTool(
                "mt-map",
                ToolMapping.template("mt_map_direction", "{mode}", "mode"),
                createMetadata("mt_map_direction"),
                mgr);

        ToolResult result = tool.execute(
                Map.of("mode", "driving", "origin", "1,2", "destination", "3,4"),
                CONTEXT);
        assertTrue(result.error()); // 因为没有真实服务器
        mgr.close();
    }

    @Test
    void execute_nullInput_noNPE() throws Exception {
        McpConnectionManager mgr = new McpConnectionManager();
        MappedMcpTool tool = new MappedMcpTool(
                "server",
                ToolMapping.literal("test", "upstream"),
                createMetadata("test"),
                mgr);

        ToolResult result = tool.execute(null, CONTEXT);
        assertTrue(result.error());
        mgr.close();
    }

    @Test
    void execute_emptyInput_noNPE() throws Exception {
        McpConnectionManager mgr = new McpConnectionManager();
        MappedMcpTool tool = new MappedMcpTool(
                "server",
                ToolMapping.literal("test", "upstream"),
                createMetadata("test"),
                mgr);

        ToolResult result = tool.execute(Map.of(), CONTEXT);
        assertTrue(result.error());
        mgr.close();
    }

    // ---- 辅助方法 ----

    private MappedMcpTool createTool(String name, ToolMapping mapping) {
        return createToolWithServer("test-server", name, mapping);
    }

    private MappedMcpTool createToolWithServer(String serverName, String name, ToolMapping mapping) {
        return new MappedMcpTool(
                serverName,
                mapping,
                createMetadata(name),
                new McpConnectionManager());
    }

    private ToolMetadata createMetadata(String name) {
        return new ToolMetadata(
                name, "Test tool description", true, true, false,
                ToolMetadata.PathDomain.NONE, null,
                List.of(new ToolMetadata.ParamInfo("param1", "Test param", true))
        );
    }
}
