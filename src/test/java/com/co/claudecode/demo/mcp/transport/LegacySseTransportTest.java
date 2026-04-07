package com.co.claudecode.demo.mcp.transport;

import com.co.claudecode.demo.mcp.McpServerConfig;
import com.co.claudecode.demo.mcp.protocol.JsonRpcRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LegacySseTransport 单元测试。
 * <p>
 * 由于没有真实 SSE 服务器，主要测试状态管理、URL 解析等。
 */
class LegacySseTransportTest {

    // ---- 状态管理 ----

    @Test
    void notOpen_beforeStart() {
        LegacySseTransport transport = createTransport("http://localhost:9999/sse");
        assertFalse(transport.isOpen());
    }

    @Test
    void start_withoutUrl_throwsIOException() {
        McpServerConfig config = McpServerConfig.sse("test", "", Map.of());
        LegacySseTransport transport = new LegacySseTransport(config);
        assertThrows(IOException.class, transport::start);
    }

    @Test
    void start_withNullUrl_throwsIOException() {
        McpServerConfig config = McpServerConfig.sse("test", null, Map.of());
        LegacySseTransport transport = new LegacySseTransport(config);
        assertThrows(IOException.class, transport::start);
    }

    @Test
    void sendRequest_whenNotOpen_throwsIOException() {
        LegacySseTransport transport = createTransport("http://localhost:9999/sse");
        assertThrows(IOException.class, () ->
                transport.sendRequest(JsonRpcRequest.of("1", "test")));
    }

    @Test
    void sendNotification_whenNotOpen_throwsIOException() {
        LegacySseTransport transport = createTransport("http://localhost:9999/sse");
        assertThrows(IOException.class, () ->
                transport.sendNotification("test", null));
    }

    @Test
    void close_whenNotStarted_noError() {
        LegacySseTransport transport = createTransport("http://localhost:9999/sse");
        assertDoesNotThrow(() -> transport.close());
    }

    @Test
    void close_idempotent() {
        LegacySseTransport transport = createTransport("http://localhost:9999/sse");
        transport.close();
        assertDoesNotThrow(() -> transport.close());
    }

    // ---- URL 解析 ----

    @Test
    void resolvePostUrl_relativePath() {
        String result = LegacySseTransport.resolvePostUrl(
                "http://10.231.105.187:8080/sse",
                "/message?sessionId=abc123");
        assertEquals("http://10.231.105.187:8080/message?sessionId=abc123", result);
    }

    @Test
    void resolvePostUrl_withHttps() {
        String result = LegacySseTransport.resolvePostUrl(
                "https://mcp.example.com/sse",
                "/message?sessionId=xyz");
        assertEquals("https://mcp.example.com/message?sessionId=xyz", result);
    }

    @Test
    void resolvePostUrl_withPort() {
        String result = LegacySseTransport.resolvePostUrl(
                "http://localhost:3456/sse",
                "/message?sessionId=test");
        assertEquals("http://localhost:3456/message?sessionId=test", result);
    }

    @Test
    void resolvePostUrl_originMismatch_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                LegacySseTransport.resolvePostUrl(
                        "http://localhost:8080/sse",
                        "http://evil.com/message?sessionId=x"));
    }

    @Test
    void resolvePostUrl_deepPath() {
        String result = LegacySseTransport.resolvePostUrl(
                "http://server.com:8080/api/v1/sse",
                "/api/v1/message?sessionId=sess123");
        assertEquals("http://server.com:8080/api/v1/message?sessionId=sess123", result);
    }

    // ---- Timeout Configuration ----

    @Test
    void endpointTimeout_is60Seconds() throws Exception {
        // 验证超时从 30s 提升到 60s（通过反射读取常量）
        Field field = LegacySseTransport.class.getDeclaredField("ENDPOINT_TIMEOUT");
        field.setAccessible(true);
        Duration timeout = (Duration) field.get(null);
        assertEquals(60, timeout.toSeconds(),
                "ENDPOINT_TIMEOUT should be 60 seconds for slow internal network services");
    }

    @Test
    void responseTimeout_is60Seconds() throws Exception {
        Field field = LegacySseTransport.class.getDeclaredField("RESPONSE_TIMEOUT");
        field.setAccessible(true);
        Duration timeout = (Duration) field.get(null);
        assertEquals(60, timeout.toSeconds());
    }

    private LegacySseTransport createTransport(String url) {
        McpServerConfig config = McpServerConfig.sse("test", url, Map.of());
        return new LegacySseTransport(config);
    }
}
