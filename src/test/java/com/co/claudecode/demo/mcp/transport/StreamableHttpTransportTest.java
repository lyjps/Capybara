package com.co.claudecode.demo.mcp.transport;

import com.co.claudecode.demo.mcp.McpServerConfig;
import com.co.claudecode.demo.mcp.protocol.JsonRpcRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StreamableHttpTransport 单元测试。
 * <p>
 * 由于没有真实 HTTP 服务器，主要测试构造和状态管理。
 */
class StreamableHttpTransportTest {

    @Test
    void notOpen_beforeStart() {
        StreamableHttpTransport transport = createTransport("http://localhost:9999");
        assertFalse(transport.isOpen());
    }

    @Test
    void start_setsOpenTrue() throws IOException {
        StreamableHttpTransport transport = createTransport("http://localhost:9999");
        transport.start();
        assertTrue(transport.isOpen());
        transport.close();
    }

    @Test
    void close_setsOpenFalse() throws IOException {
        StreamableHttpTransport transport = createTransport("http://localhost:9999");
        transport.start();
        transport.close();
        assertFalse(transport.isOpen());
    }

    @Test
    void start_withoutUrl_throwsIOException() {
        McpServerConfig config = McpServerConfig.sse("test", "", Map.of());
        StreamableHttpTransport transport = new StreamableHttpTransport(config);
        assertThrows(IOException.class, transport::start);
    }

    @Test
    void sendRequest_whenNotOpen_throwsIOException() {
        StreamableHttpTransport transport = createTransport("http://localhost:9999");
        assertThrows(IOException.class, () ->
                transport.sendRequest(JsonRpcRequest.of("1", "test")));
    }

    @Test
    void sendRequest_toNonexistentServer_throwsIOException() throws IOException {
        StreamableHttpTransport transport = createTransport("http://localhost:19999/nonexistent");
        transport.start();
        try {
            assertThrows(IOException.class, () ->
                    transport.sendRequest(JsonRpcRequest.of("1", "test")));
        } finally {
            transport.close();
        }
    }

    @Test
    void close_idempotent() throws IOException {
        StreamableHttpTransport transport = createTransport("http://localhost:9999");
        transport.start();
        transport.close();
        transport.close(); // Should not throw
        assertFalse(transport.isOpen());
    }

    @Test
    void sendNotification_whenNotOpen_throwsIOException() {
        StreamableHttpTransport transport = createTransport("http://localhost:9999");
        assertThrows(IOException.class, () ->
                transport.sendNotification("test", null));
    }

    @Test
    void constructor_withDynamicHeaders() throws IOException {
        Map<String, String> headers = Map.of("Authorization", "Bearer test-token");
        StreamableHttpTransport transport = new StreamableHttpTransport(
                McpServerConfig.http("test", "http://localhost:9999", Map.of()),
                () -> headers);
        transport.start();
        assertTrue(transport.isOpen());
        transport.close();
    }

    @Test
    void constructor_withNullDynamicHeaders() throws IOException {
        StreamableHttpTransport transport = new StreamableHttpTransport(
                McpServerConfig.http("test", "http://localhost:9999", Map.of()),
                null);
        transport.start();
        assertTrue(transport.isOpen());
        transport.close();
    }

    // ---- Session ID Management ----

    @Test
    void sessionId_nullBeforeStart() {
        StreamableHttpTransport transport = createTransport("http://localhost:9999");
        assertNull(transport.getSessionId());
    }

    @Test
    void sessionId_nullAfterStart() throws IOException {
        StreamableHttpTransport transport = createTransport("http://localhost:9999");
        transport.start();
        try {
            assertNull(transport.getSessionId(),
                    "Session ID should be null before any server interaction");
        } finally {
            transport.close();
        }
    }

    @Test
    void sessionId_clearedOnClose() throws IOException {
        StreamableHttpTransport transport = createTransport("http://localhost:9999");
        transport.start();
        transport.close();
        assertNull(transport.getSessionId(),
                "Session ID should be cleared on close");
    }

    private StreamableHttpTransport createTransport(String url) {
        McpServerConfig config = McpServerConfig.sse("test", url, Map.of());
        return new StreamableHttpTransport(config);
    }
}
