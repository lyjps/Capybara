package com.co.claudecode.demo.mcp.client;

import com.co.claudecode.demo.mcp.McpConnectionState;
import com.co.claudecode.demo.mcp.McpServerConfig;
import com.co.claudecode.demo.mcp.McpServerConnection;
import com.co.claudecode.demo.mcp.McpTransportType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * McpConnectionManager 单元测试。
 * <p>
 * 注意：这些测试不启动真实的 MCP 服务器进程。
 * 主要测试连接管理逻辑、状态管理和错误处理。
 */
class McpConnectionManagerTest {

    @Test
    void connectAll_emptyList() {
        McpConnectionManager mgr = new McpConnectionManager();
        List<McpServerConnection> conns = mgr.connectAll(List.of());
        assertTrue(conns.isEmpty());
        mgr.close();
    }

    @Test
    void connectAll_nullList() {
        McpConnectionManager mgr = new McpConnectionManager();
        List<McpServerConnection> conns = mgr.connectAll(null);
        assertTrue(conns.isEmpty());
        mgr.close();
    }

    @Test
    void connectAll_disabledServer() {
        McpConnectionManager mgr = new McpConnectionManager();
        McpServerConfig config = McpServerConfig.stdio("disabled-server", "node", List.of(), Map.of())
                .asDisabled();
        List<McpServerConnection> conns = mgr.connectAll(List.of(config));
        assertEquals(1, conns.size());
        assertEquals(McpConnectionState.DISABLED, conns.get(0).state());
        mgr.close();
    }

    @Test
    void connectAll_failedConnection() {
        // Using a nonexistent command should cause connection failure
        McpConnectionManager mgr = new McpConnectionManager();
        McpServerConfig config = McpServerConfig.stdio("bad-server",
                "nonexistent_command_xyz_12345", List.of(), Map.of());
        mgr.connectAll(List.of(config));

        McpServerConnection conn = mgr.getConnection("bad-server");
        assertNotNull(conn);
        assertEquals(McpConnectionState.FAILED, conn.state());
        assertNotNull(conn.errorMessage());
        mgr.close();
    }

    @Test
    void connect_singleServer_failed() {
        McpConnectionManager mgr = new McpConnectionManager();
        McpServerConfig config = McpServerConfig.stdio("test",
                "nonexistent_command_xyz_12345", List.of(), Map.of());
        McpServerConnection conn = mgr.connect(config);
        assertEquals(McpConnectionState.FAILED, conn.state());
        mgr.close();
    }

    @Test
    void connect_disabledSingle() {
        McpConnectionManager mgr = new McpConnectionManager();
        McpServerConfig config = McpServerConfig.stdio("test", "node", List.of(), Map.of())
                .asDisabled();
        McpServerConnection conn = mgr.connect(config);
        assertEquals(McpConnectionState.DISABLED, conn.state());
        mgr.close();
    }

    @Test
    void allConnections_returnsAll() {
        McpConnectionManager mgr = new McpConnectionManager();
        mgr.connect(McpServerConfig.stdio("s1", "bad_cmd_1", List.of(), Map.of()));
        mgr.connect(McpServerConfig.stdio("s2", "bad_cmd_2", List.of(), Map.of()));
        assertEquals(2, mgr.allConnections().size());
        mgr.close();
    }

    @Test
    void getConnection_byName() {
        McpConnectionManager mgr = new McpConnectionManager();
        mgr.connect(McpServerConfig.stdio("my-server", "bad_cmd", List.of(), Map.of()));
        assertNotNull(mgr.getConnection("my-server"));
        assertNull(mgr.getConnection("nonexistent"));
        mgr.close();
    }

    @Test
    void getAllTools_emptyWhenNoConnected() {
        McpConnectionManager mgr = new McpConnectionManager();
        mgr.connect(McpServerConfig.stdio("s", "bad_cmd", List.of(), Map.of()));
        assertTrue(mgr.getAllTools().isEmpty());
        mgr.close();
    }

    @Test
    void getAllResources_emptyWhenNoConnected() {
        McpConnectionManager mgr = new McpConnectionManager();
        assertFalse(mgr.hasAnyResources());
        mgr.close();
    }

    @Test
    void callTool_serverNotFound_throwsIOException() {
        McpConnectionManager mgr = new McpConnectionManager();
        assertThrows(Exception.class, () -> mgr.callTool("missing", "tool", "{}"));
        mgr.close();
    }

    @Test
    void readResource_serverNotFound_throwsIOException() {
        McpConnectionManager mgr = new McpConnectionManager();
        assertThrows(Exception.class, () -> mgr.readResource("missing", "uri://test"));
        mgr.close();
    }

    @Test
    void close_clearsConnections() {
        McpConnectionManager mgr = new McpConnectionManager();
        mgr.connect(McpServerConfig.stdio("s1", "bad_cmd", List.of(), Map.of()));
        mgr.close();
        assertTrue(mgr.allConnections().isEmpty());
    }

    // ---- Constants ----

    @Test
    void constants_matchTsValues() {
        assertEquals(3, McpConnectionManager.STDIO_BATCH_SIZE);
        assertEquals(10, McpConnectionManager.REMOTE_BATCH_SIZE);
        assertEquals(5, McpConnectionManager.MAX_RECONNECT_ATTEMPTS);
        assertEquals(1000, McpConnectionManager.INITIAL_BACKOFF_MS);
        assertEquals(30_000, McpConnectionManager.MAX_BACKOFF_MS);
    }
}
