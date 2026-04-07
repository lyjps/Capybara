package com.co.claudecode.demo.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * McpServerConfig 单元测试。
 */
class McpServerConfigTest {

    @Test
    void stdio_factoryMethod() {
        McpServerConfig config = McpServerConfig.stdio("test", "node",
                List.of("server.js"), Map.of("PORT", "3000"));
        assertEquals("test", config.name());
        assertEquals(McpTransportType.STDIO, config.transportType());
        assertEquals("node", config.command());
        assertEquals(List.of("server.js"), config.args());
        assertEquals("3000", config.env().get("PORT"));
        assertFalse(config.disabled());
    }

    @Test
    void sse_factoryMethod() {
        McpServerConfig config = McpServerConfig.sse("test", "http://localhost:3000",
                Map.of("Authorization", "Bearer token"));
        assertEquals(McpTransportType.SSE, config.transportType());
        assertEquals("http://localhost:3000", config.url());
        assertEquals("Bearer token", config.headers().get("Authorization"));
    }

    @Test
    void http_factoryMethod() {
        McpServerConfig config = McpServerConfig.http("test", "http://localhost:8080", Map.of());
        assertEquals(McpTransportType.HTTP, config.transportType());
    }

    @Test
    void asDisabled_createsDisabledCopy() {
        McpServerConfig config = McpServerConfig.stdio("test", "node", List.of(), Map.of());
        assertFalse(config.disabled());
        McpServerConfig disabled = config.asDisabled();
        assertTrue(disabled.disabled());
        assertEquals("test", disabled.name());
    }

    @Test
    void isLocal_stdioIsLocal() {
        McpServerConfig config = McpServerConfig.stdio("test", "node", List.of(), Map.of());
        assertTrue(config.isLocal());
        assertFalse(config.isRemote());
    }

    @Test
    void isRemote_sseIsRemote() {
        McpServerConfig config = McpServerConfig.sse("test", "http://host", Map.of());
        assertTrue(config.isRemote());
        assertFalse(config.isLocal());
    }

    @Test
    void isRemote_httpIsRemote() {
        McpServerConfig config = McpServerConfig.http("test", "http://host", Map.of());
        assertTrue(config.isRemote());
    }

    @Test
    void nullArgs_defaultsToEmptyList() {
        McpServerConfig config = new McpServerConfig("test", McpTransportType.STDIO,
                "cmd", null, null, null, null, null, false);
        assertNotNull(config.args());
        assertTrue(config.args().isEmpty());
        assertNotNull(config.env());
        assertNotNull(config.headers());
    }

    @Test
    void httpWithAuth_factoryMethod() {
        com.co.claudecode.demo.mcp.auth.McpAuthConfig auth =
                new com.co.claudecode.demo.mcp.auth.McpAuthConfig(
                        "https://token.example.com", "client1", "secret1", "audience1");
        McpServerConfig config = McpServerConfig.httpWithAuth(
                "mt-map", "http://mcp.example.com", Map.of(), auth);
        assertEquals(McpTransportType.HTTP, config.transportType());
        assertNotNull(config.auth());
        assertEquals("client1", config.auth().clientId());
    }

    @Test
    void auth_nullByDefault() {
        McpServerConfig config = McpServerConfig.stdio("test", "node", List.of(), Map.of());
        assertNull(config.auth());
    }
}
