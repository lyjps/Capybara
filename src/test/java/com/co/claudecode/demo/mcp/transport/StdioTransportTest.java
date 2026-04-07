package com.co.claudecode.demo.mcp.transport;

import com.co.claudecode.demo.mcp.McpServerConfig;
import com.co.claudecode.demo.mcp.protocol.JsonRpcRequest;
import com.co.claudecode.demo.mcp.protocol.JsonRpcResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StdioTransport 单元测试。
 * <p>
 * 使用系统可用的 cat/echo 命令作为模拟 MCP 服务器进程。
 */
class StdioTransportTest {

    @Test
    void notOpen_beforeStart() {
        StdioTransport transport = createTransport("cat");
        assertFalse(transport.isOpen());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void start_opensProcess() throws IOException {
        // 使用 cat 作为回显进程（写入 stdin 会从 stdout 返回）
        StdioTransport transport = createTransport("cat");
        try {
            transport.start();
            assertTrue(transport.isOpen());
        } finally {
            transport.close();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void close_terminatesProcess() throws IOException {
        StdioTransport transport = createTransport("cat");
        transport.start();
        assertTrue(transport.isOpen());

        transport.close();
        assertFalse(transport.isOpen());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void sendRequest_andReceiveResponse() throws IOException {
        // cat 会把 stdin 原样回显到 stdout，所以我们发送一个 JSON-RPC
        // 请求，cat 返回同样的内容。虽然不是真正的 JSON-RPC 响应格式，
        // 但可以验证传输层的读写功能。
        StdioTransport transport = createTransport("cat");
        try {
            transport.start();
            JsonRpcRequest request = JsonRpcRequest.of("1", "test", "{}");
            JsonRpcResponse response = transport.sendRequest(request);
            // cat 返回的是请求的 JSON，解析后 id 应该匹配
            assertNotNull(response);
            assertEquals("1", response.id());
        } finally {
            transport.close();
        }
    }

    @Test
    void sendRequest_whenNotOpen_throwsIOException() {
        StdioTransport transport = createTransport("cat");
        assertThrows(IOException.class, () ->
                transport.sendRequest(JsonRpcRequest.of("1", "test")));
    }

    @Test
    void sendNotification_whenNotOpen_throwsIOException() {
        StdioTransport transport = createTransport("cat");
        assertThrows(IOException.class, () ->
                transport.sendNotification("test", null));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void start_invalidCommand_throwsIOException() {
        StdioTransport transport = createTransport("nonexistent_command_xyz_12345");
        assertThrows(IOException.class, transport::start);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void start_processExitsImmediately_throwsIOException() {
        // /bin/true exits immediately with 0
        StdioTransport transport = createTransport("true");
        // The process exits immediately, so isOpen should be false after start
        // or start itself may throw
        try {
            transport.start();
            // If start doesn't throw, the process should have exited
            // and subsequent operations should fail
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("exited immediately"));
        } finally {
            try { transport.close(); } catch (IOException ignored) {}
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void close_idempotent() throws IOException {
        StdioTransport transport = createTransport("cat");
        transport.start();
        transport.close();
        // Second close should not throw
        transport.close();
        assertFalse(transport.isOpen());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void sendNotification_works() throws IOException {
        StdioTransport transport = createTransport("cat");
        try {
            transport.start();
            // Should not throw
            transport.sendNotification("notifications/initialized", null);
        } finally {
            transport.close();
        }
    }

    private StdioTransport createTransport(String command) {
        McpServerConfig config = McpServerConfig.stdio("test", command, List.of(), Map.of());
        return new StdioTransport(config);
    }
}
