package com.co.claudecode.demo.mcp.tool;

import com.co.claudecode.demo.mcp.client.McpConnectionManager;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ListMcpResourcesTool 单元测试。
 */
class ListMcpResourcesToolTest {

    private final ToolExecutionContext ctx = new ToolExecutionContext(Path.of("."), Path.of("output"));

    @Test
    void metadata_correctName() {
        McpConnectionManager mgr = new McpConnectionManager();
        ListMcpResourcesTool tool = new ListMcpResourcesTool(mgr);
        assertEquals("mcp_list_resources", tool.metadata().name());
        assertTrue(tool.metadata().readOnly());
        assertTrue(tool.metadata().concurrencySafe());
        mgr.close();
    }

    @Test
    void metadata_hasServerParam() {
        McpConnectionManager mgr = new McpConnectionManager();
        ListMcpResourcesTool tool = new ListMcpResourcesTool(mgr);
        assertEquals(1, tool.metadata().params().size());
        assertEquals("server", tool.metadata().params().get(0).name());
        assertFalse(tool.metadata().params().get(0).required());
        mgr.close();
    }

    @Test
    void execute_noConnections_returnsNoResources() throws Exception {
        McpConnectionManager mgr = new McpConnectionManager();
        ListMcpResourcesTool tool = new ListMcpResourcesTool(mgr);
        ToolResult result = tool.execute(Map.of(), ctx);
        assertFalse(result.error());
        assertTrue(result.content().contains("No resources found"));
        mgr.close();
    }

    @Test
    void execute_withServerFilter_passesFilter() throws Exception {
        McpConnectionManager mgr = new McpConnectionManager();
        ListMcpResourcesTool tool = new ListMcpResourcesTool(mgr);
        ToolResult result = tool.execute(Map.of("server", "nonexistent"), ctx);
        assertFalse(result.error());
        assertTrue(result.content().contains("No resources found"));
        mgr.close();
    }

    @Test
    void execute_emptyServerFilter_listsAll() throws Exception {
        McpConnectionManager mgr = new McpConnectionManager();
        ListMcpResourcesTool tool = new ListMcpResourcesTool(mgr);
        ToolResult result = tool.execute(Map.of("server", ""), ctx);
        assertFalse(result.error());
        assertTrue(result.content().contains("No resources found"));
        mgr.close();
    }
}
