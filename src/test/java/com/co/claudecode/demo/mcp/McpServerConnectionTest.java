package com.co.claudecode.demo.mcp;

import com.co.claudecode.demo.mcp.client.McpInitResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * McpServerConnection 单元测试。
 */
class McpServerConnectionTest {

    @Test
    void initialState_pending() {
        McpServerConfig config = McpServerConfig.stdio("test", "node", List.of(), Map.of());
        McpServerConnection conn = new McpServerConnection("test", config);
        assertEquals(McpConnectionState.PENDING, conn.state());
        assertFalse(conn.isConnected());
    }

    @Test
    void initialState_disabledConfig() {
        McpServerConfig config = McpServerConfig.stdio("test", "node", List.of(), Map.of()).asDisabled();
        McpServerConnection conn = new McpServerConnection("test", config);
        assertEquals(McpConnectionState.DISABLED, conn.state());
    }

    @Test
    void markConnected_updatesState() {
        McpServerConnection conn = createPendingConnection();
        McpInitResult result = new McpInitResult("server", "1.0", "inst", true, false, false);
        conn.markConnected(null, result);
        assertEquals(McpConnectionState.CONNECTED, conn.state());
        assertTrue(conn.isConnected());
        assertEquals("server", conn.serverName());
        assertEquals("1.0", conn.serverVersion());
        assertEquals("inst", conn.instructions());
        assertNull(conn.errorMessage());
    }

    @Test
    void markFailed_updatesState() {
        McpServerConnection conn = createPendingConnection();
        conn.markFailed("Connection refused");
        assertEquals(McpConnectionState.FAILED, conn.state());
        assertEquals("Connection refused", conn.errorMessage());
        assertFalse(conn.isConnected());
    }

    @Test
    void markPending_incrementsReconnectAttempt() {
        McpServerConnection conn = createPendingConnection();
        assertEquals(0, conn.reconnectAttempt());
        conn.markPending();
        assertEquals(1, conn.reconnectAttempt());
        conn.markPending();
        assertEquals(2, conn.reconnectAttempt());
    }

    @Test
    void markConnected_resetsReconnectAttempt() {
        McpServerConnection conn = createPendingConnection();
        conn.markPending();
        conn.markPending();
        McpInitResult result = new McpInitResult("s", "1", null, true, false, false);
        conn.markConnected(null, result);
        assertEquals(0, conn.reconnectAttempt());
    }

    @Test
    void setTools_updatesToolList() {
        McpServerConnection conn = createPendingConnection();
        assertTrue(conn.tools().isEmpty());

        McpToolInfo tool = new McpToolInfo("get", "desc", "{}", List.of(), false);
        conn.setTools(List.of(tool));
        assertEquals(1, conn.tools().size());
    }

    @Test
    void setResources_updatesResourceList() {
        McpServerConnection conn = createPendingConnection();
        assertFalse(conn.hasResources());

        conn.setResources(List.of(new McpResourceInfo("uri://test", "test", null, null)));
        assertTrue(conn.hasResources());
        assertEquals(1, conn.resources().size());
    }

    @Test
    void formatStatus_connected() {
        McpServerConnection conn = createPendingConnection();
        McpInitResult result = new McpInitResult("srv", "2.0", null, true, true, false);
        conn.markConnected(null, result);
        conn.setTools(List.of(new McpToolInfo("t1", "", "{}", List.of(), false)));

        String status = conn.formatStatus();
        assertTrue(status.contains("test"));
        assertTrue(status.contains("CONNECTED"));
        assertTrue(status.contains("tools=1"));
        assertTrue(status.contains("version=2.0"));
    }

    @Test
    void formatStatus_failed() {
        McpServerConnection conn = createPendingConnection();
        conn.markFailed("timeout");
        String status = conn.formatStatus();
        assertTrue(status.contains("FAILED"));
        assertTrue(status.contains("timeout"));
    }

    private McpServerConnection createPendingConnection() {
        McpServerConfig config = McpServerConfig.stdio("test", "node", List.of(), Map.of());
        return new McpServerConnection("test", config);
    }
}
