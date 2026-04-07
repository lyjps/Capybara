package com.co.claudecode.demo.mcp.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * McpAuthConfig 单元测试。
 */
class McpAuthConfigTest {

    @Test
    void validConfig() {
        McpAuthConfig config = new McpAuthConfig(
                "https://token.example.com", "client1", "secret1", "audience1");
        assertTrue(config.isValid());
    }

    @Test
    void invalidConfig_nullFields() {
        assertFalse(new McpAuthConfig(null, "c", "s", "a").isValid());
        assertFalse(new McpAuthConfig("t", null, "s", "a").isValid());
        assertFalse(new McpAuthConfig("t", "c", null, "a").isValid());
        assertFalse(new McpAuthConfig("t", "c", "s", null).isValid());
    }

    @Test
    void invalidConfig_blankFields() {
        assertFalse(new McpAuthConfig("", "c", "s", "a").isValid());
        assertFalse(new McpAuthConfig("t", "", "s", "a").isValid());
        assertFalse(new McpAuthConfig("t", "c", "", "a").isValid());
        assertFalse(new McpAuthConfig("t", "c", "s", "").isValid());
    }

    @Test
    void recordAccessors() {
        McpAuthConfig config = new McpAuthConfig("ep", "cid", "cs", "aud");
        assertEquals("ep", config.tokenEndpoint());
        assertEquals("cid", config.clientId());
        assertEquals("cs", config.clientSecret());
        assertEquals("aud", config.audience());
    }
}
