package com.co.claudecode.demo.mcp.tool;

import com.co.claudecode.demo.mcp.client.McpConnectionManager;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReadMcpResourceTool 单元测试。
 */
class ReadMcpResourceToolTest {

    private final ToolExecutionContext ctx = new ToolExecutionContext(Path.of("."), Path.of("output"));

    @Test
    void metadata_correctName() {
        McpConnectionManager mgr = new McpConnectionManager();
        ReadMcpResourceTool tool = new ReadMcpResourceTool(mgr);
        assertEquals("mcp_read_resource", tool.metadata().name());
        assertTrue(tool.metadata().readOnly());
        mgr.close();
    }

    @Test
    void metadata_hasRequiredParams() {
        McpConnectionManager mgr = new McpConnectionManager();
        ReadMcpResourceTool tool = new ReadMcpResourceTool(mgr);
        assertEquals(2, tool.metadata().params().size());
        assertTrue(tool.metadata().params().get(0).required()); // server
        assertTrue(tool.metadata().params().get(1).required()); // uri
        mgr.close();
    }

    @Test
    void validate_missingServer_throws() {
        McpConnectionManager mgr = new McpConnectionManager();
        ReadMcpResourceTool tool = new ReadMcpResourceTool(mgr);
        assertThrows(IllegalArgumentException.class, () ->
                tool.validate(Map.of("uri", "file:///test")));
        mgr.close();
    }

    @Test
    void validate_missingUri_throws() {
        McpConnectionManager mgr = new McpConnectionManager();
        ReadMcpResourceTool tool = new ReadMcpResourceTool(mgr);
        assertThrows(IllegalArgumentException.class, () ->
                tool.validate(Map.of("server", "test")));
        mgr.close();
    }

    @Test
    void execute_serverNotFound_returnsError() throws Exception {
        McpConnectionManager mgr = new McpConnectionManager();
        ReadMcpResourceTool tool = new ReadMcpResourceTool(mgr);
        ToolResult result = tool.execute(
                Map.of("server", "nonexistent", "uri", "file:///test"), ctx);
        assertTrue(result.error());
        assertTrue(result.content().contains("Failed to read resource"));
        mgr.close();
    }

    @Test
    void validate_validInput_noThrow() {
        McpConnectionManager mgr = new McpConnectionManager();
        ReadMcpResourceTool tool = new ReadMcpResourceTool(mgr);
        assertDoesNotThrow(() ->
                tool.validate(Map.of("server", "test", "uri", "file:///test")));
        mgr.close();
    }
}
