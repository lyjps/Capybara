package com.co.claudecode.demo.mcp.tool;

import com.co.claudecode.demo.mcp.McpNameUtils;
import com.co.claudecode.demo.mcp.McpToolInfo;
import com.co.claudecode.demo.mcp.client.McpConnectionManager;
import com.co.claudecode.demo.tool.Tool;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolMetadata;
import com.co.claudecode.demo.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * McpToolBridge 单元测试。
 */
class McpToolBridgeTest {

    @Test
    void metadata_hasCorrectName() {
        McpToolBridge bridge = createBridge("my-server", "get_file");
        String expected = McpNameUtils.buildToolName("my-server", "get_file");
        assertEquals(expected, bridge.metadata().name());
    }

    @Test
    void metadata_hasDescription() {
        McpToolBridge bridge = createBridge("server", "tool");
        assertEquals("Test tool description", bridge.metadata().description());
    }

    @Test
    void metadata_truncatesLongDescription() {
        String longDesc = "x".repeat(3000);
        McpToolInfo toolInfo = new McpToolInfo("tool", longDesc, "{}", List.of(), false);
        McpToolBridge bridge = new McpToolBridge("server", toolInfo, new McpConnectionManager());
        assertTrue(bridge.metadata().description().length() <= 2048);
    }

    @Test
    void metadata_mapsParams() {
        List<McpToolInfo.McpParamInfo> params = List.of(
                new McpToolInfo.McpParamInfo("path", "string", "File path", true),
                new McpToolInfo.McpParamInfo("encoding", "string", "Encoding", false)
        );
        McpToolInfo toolInfo = new McpToolInfo("read", "Read", "{}", params, false);
        McpToolBridge bridge = new McpToolBridge("server", toolInfo, new McpConnectionManager());

        List<ToolMetadata.ParamInfo> metaParams = bridge.metadata().params();
        assertEquals(2, metaParams.size());
        assertEquals("path", metaParams.get(0).name());
        assertTrue(metaParams.get(0).required());
        assertEquals("encoding", metaParams.get(1).name());
        assertFalse(metaParams.get(1).required());
    }

    @Test
    void metadata_readOnlyTool() {
        McpToolInfo toolInfo = new McpToolInfo("read", "Read", "{}", List.of(), true);
        McpToolBridge bridge = new McpToolBridge("server", toolInfo, new McpConnectionManager());
        assertTrue(bridge.metadata().readOnly());
        assertTrue(bridge.metadata().concurrencySafe());
    }

    @Test
    void metadata_writableTool() {
        McpToolInfo toolInfo = new McpToolInfo("write", "Write", "{}", List.of(), false);
        McpToolBridge bridge = new McpToolBridge("server", toolInfo, new McpConnectionManager());
        assertFalse(bridge.metadata().readOnly());
        assertFalse(bridge.metadata().concurrencySafe());
    }

    @Test
    void metadata_pathDomainIsNone() {
        McpToolBridge bridge = createBridge("server", "tool");
        assertEquals(ToolMetadata.PathDomain.NONE, bridge.metadata().pathDomain());
    }

    @Test
    void execute_failedConnection_returnsError() throws Exception {
        // Using a manager with no connections should return an error ToolResult
        McpConnectionManager mgr = new McpConnectionManager();
        McpToolInfo toolInfo = new McpToolInfo("tool", "desc", "{}", List.of(), false);
        McpToolBridge bridge = new McpToolBridge("nonexistent", toolInfo, mgr);

        ToolExecutionContext ctx = new ToolExecutionContext(Path.of("."), Path.of("output"));
        ToolResult result = bridge.execute(Map.of("key", "value"), ctx);
        assertTrue(result.error());
        assertTrue(result.content().contains("MCP tool error"));
        mgr.close();
    }

    @Test
    void serverName_returnsOriginal() {
        McpToolBridge bridge = createBridge("my-server", "tool");
        assertEquals("my-server", bridge.serverName());
    }

    @Test
    void toolInfo_returnsOriginal() {
        McpToolBridge bridge = createBridge("server", "my-tool");
        assertEquals("my-tool", bridge.toolInfo().name());
    }

    @Test
    void createAll_emptyManager() {
        McpConnectionManager mgr = new McpConnectionManager();
        List<Tool> tools = McpToolBridge.createAll(mgr);
        assertTrue(tools.isEmpty());
        mgr.close();
    }

    private McpToolBridge createBridge(String serverName, String toolName) {
        McpToolInfo toolInfo = new McpToolInfo(toolName, "Test tool description", "{}",
                List.of(new McpToolInfo.McpParamInfo("input", "string", "Input param", true)),
                false);
        return new McpToolBridge(serverName, toolInfo, new McpConnectionManager());
    }
}
