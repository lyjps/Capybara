package com.co.claudecode.demo.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * McpNameUtils 单元测试。
 */
class McpNameUtilsTest {

    // ---- normalizeForMcp ----

    @Test
    void normalize_nullReturnsEmpty() {
        assertEquals("", McpNameUtils.normalizeForMcp(null));
        assertEquals("", McpNameUtils.normalizeForMcp(""));
    }

    @Test
    void normalize_validCharsUnchanged() {
        assertEquals("my-server_1", McpNameUtils.normalizeForMcp("my-server_1"));
        assertEquals("ABC", McpNameUtils.normalizeForMcp("ABC"));
    }

    @Test
    void normalize_specialCharsReplaced() {
        assertEquals("my_server_name", McpNameUtils.normalizeForMcp("my.server name"));
        assertEquals("test_tool", McpNameUtils.normalizeForMcp("test@tool"));
        assertEquals("path_to_file", McpNameUtils.normalizeForMcp("path/to/file"));
    }

    @Test
    void normalize_chineseCharsReplaced() {
        String result = McpNameUtils.normalizeForMcp("测试服务");
        assertFalse(result.contains("测"));
        assertTrue(result.contains("_"));
    }

    @Test
    void normalize_truncatedTo64Chars() {
        String longName = "a".repeat(100);
        assertEquals(64, McpNameUtils.normalizeForMcp(longName).length());
    }

    // ---- buildToolName ----

    @Test
    void buildToolName_standard() {
        // hyphen is a valid character, so "get-file" stays as "get-file"
        assertEquals("mcp__my_server__get-file",
                McpNameUtils.buildToolName("my server", "get-file"));
    }

    @Test
    void buildToolName_alreadyNormalized() {
        assertEquals("mcp__server__tool",
                McpNameUtils.buildToolName("server", "tool"));
    }

    // ---- getMcpPrefix ----

    @Test
    void getMcpPrefix_standard() {
        assertEquals("mcp__my_server__",
                McpNameUtils.getMcpPrefix("my server"));
    }

    // ---- parseToolName ----

    @Test
    void parseToolName_valid() {
        McpNameUtils.McpToolRef ref = McpNameUtils.parseToolName("mcp__server__tool");
        assertNotNull(ref);
        assertEquals("server", ref.serverName());
        assertEquals("tool", ref.toolName());
    }

    @Test
    void parseToolName_longToolName() {
        McpNameUtils.McpToolRef ref = McpNameUtils.parseToolName("mcp__server__my_tool_name");
        assertNotNull(ref);
        assertEquals("server", ref.serverName());
        assertEquals("my_tool_name", ref.toolName());
    }

    @Test
    void parseToolName_notMcpReturnsNull() {
        assertNull(McpNameUtils.parseToolName("read_file"));
        assertNull(McpNameUtils.parseToolName(null));
    }

    @Test
    void parseToolName_incompleteReturnsNull() {
        assertNull(McpNameUtils.parseToolName("mcp__"));
        assertNull(McpNameUtils.parseToolName("mcp__server__"));
    }

    // ---- isMcpTool ----

    @Test
    void isMcpTool_checks() {
        assertTrue(McpNameUtils.isMcpTool("mcp__server__tool"));
        assertFalse(McpNameUtils.isMcpTool("read_file"));
        assertFalse(McpNameUtils.isMcpTool(null));
    }

    // ---- isToolFromServer ----

    @Test
    void isToolFromServer_matches() {
        assertTrue(McpNameUtils.isToolFromServer("mcp__my_server__tool1", "my server"));
        assertFalse(McpNameUtils.isToolFromServer("mcp__other__tool1", "my server"));
    }

    // ---- displayName ----

    @Test
    void displayName_format() {
        assertEquals("server - tool (MCP)", McpNameUtils.displayName("server", "tool"));
    }
}
