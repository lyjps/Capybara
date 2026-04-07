package com.co.claudecode.demo.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * McpConfigLoader 单元测试。
 */
class McpConfigLoaderTest {

    @TempDir
    Path tempDir;

    // ---- parseMcpServersJson ----

    @Test
    void parseMcpServersJson_stdioConfig() {
        String json = """
                {
                  "mcpServers": {
                    "my-server": {
                      "type": "stdio",
                      "command": "node",
                      "args": ["server.js", "--port", "3000"],
                      "env": {"NODE_ENV": "production"}
                    }
                  }
                }
                """;
        List<McpServerConfig> configs = McpConfigLoader.parseMcpServersJson(json);
        assertEquals(1, configs.size());

        McpServerConfig config = configs.get(0);
        assertEquals("my-server", config.name());
        assertEquals(McpTransportType.STDIO, config.transportType());
        assertEquals("node", config.command());
        assertTrue(config.args().contains("server.js"));
    }

    @Test
    void parseMcpServersJson_sseConfig() {
        String json = """
                {
                  "mcpServers": {
                    "remote": {
                      "type": "sse",
                      "url": "http://localhost:3000/sse",
                      "headers": {"Authorization": "Bearer test"}
                    }
                  }
                }
                """;
        List<McpServerConfig> configs = McpConfigLoader.parseMcpServersJson(json);
        assertEquals(1, configs.size());
        assertEquals(McpTransportType.SSE, configs.get(0).transportType());
        assertEquals("http://localhost:3000/sse", configs.get(0).url());
    }

    @Test
    void parseMcpServersJson_defaultTypeIsStdio() {
        String json = """
                {
                  "mcpServers": {
                    "test": {
                      "command": "python",
                      "args": ["-m", "mcp_server"]
                    }
                  }
                }
                """;
        List<McpServerConfig> configs = McpConfigLoader.parseMcpServersJson(json);
        assertEquals(1, configs.size());
        assertEquals(McpTransportType.STDIO, configs.get(0).transportType());
    }

    @Test
    void parseMcpServersJson_multipleServers() {
        String json = """
                {
                  "mcpServers": {
                    "server1": {"command": "node", "args": ["s1.js"]},
                    "server2": {"command": "python", "args": ["s2.py"]}
                  }
                }
                """;
        List<McpServerConfig> configs = McpConfigLoader.parseMcpServersJson(json);
        assertEquals(2, configs.size());
    }

    @Test
    void parseMcpServersJson_emptyServersSection() {
        String json = "{\"mcpServers\":{}}";
        List<McpServerConfig> configs = McpConfigLoader.parseMcpServersJson(json);
        assertTrue(configs.isEmpty());
    }

    @Test
    void parseMcpServersJson_noServersKey() {
        String json = "{\"otherConfig\": true}";
        List<McpServerConfig> configs = McpConfigLoader.parseMcpServersJson(json);
        assertTrue(configs.isEmpty());
    }

    @Test
    void parseMcpServersJson_nullInput() {
        assertTrue(McpConfigLoader.parseMcpServersJson(null).isEmpty());
        assertTrue(McpConfigLoader.parseMcpServersJson("").isEmpty());
    }

    @Test
    void parseMcpServersJson_disabledServer() {
        String json = """
                {
                  "mcpServers": {
                    "test": {
                      "command": "node",
                      "args": ["s.js"],
                      "disabled": true
                    }
                  }
                }
                """;
        List<McpServerConfig> configs = McpConfigLoader.parseMcpServersJson(json);
        assertEquals(1, configs.size());
        assertTrue(configs.get(0).disabled());
    }

    // ---- loadConfigs with file ----

    @Test
    void loadConfigs_fromMcpJsonFile() throws IOException {
        String json = """
                {
                  "mcpServers": {
                    "file-server": {
                      "command": "node",
                      "args": ["fs.js"]
                    }
                  }
                }
                """;
        Files.writeString(tempDir.resolve(".mcp.json"), json);

        List<McpServerConfig> configs = McpConfigLoader.loadConfigs(tempDir);
        assertEquals(1, configs.size());
        assertEquals("file-server", configs.get(0).name());
    }

    @Test
    void loadConfigs_noConfigFiles() {
        List<McpServerConfig> configs = McpConfigLoader.loadConfigs(tempDir);
        assertTrue(configs.isEmpty());
    }

    // ---- expandEnvVars ----

    @Test
    void expandEnvVars_noVarsUnchanged() {
        assertEquals("hello", McpConfigLoader.expandEnvVars("hello"));
    }

    @Test
    void expandEnvVars_knownVar() {
        // HOME is always set
        String result = McpConfigLoader.expandEnvVars("${HOME}/test");
        assertFalse(result.contains("${HOME}"));
        assertTrue(result.endsWith("/test"));
    }

    @Test
    void expandEnvVars_unknownVarPreserved() {
        String result = McpConfigLoader.expandEnvVars("${VERY_UNLIKELY_VAR_XYZ_123}");
        assertEquals("${VERY_UNLIKELY_VAR_XYZ_123}", result);
    }

    @Test
    void expandEnvVars_defaultValue() {
        String result = McpConfigLoader.expandEnvVars("${VERY_UNLIKELY_VAR_XYZ_123:-fallback}");
        assertEquals("fallback", result);
    }

    @Test
    void expandEnvVars_nullReturnsNull() {
        assertNull(McpConfigLoader.expandEnvVars(null));
    }

    // ---- expandConfig ----

    @Test
    void expandConfig_expandsStdioFields() {
        McpServerConfig config = McpServerConfig.stdio("test", "${HOME}/bin/node",
                List.of("${HOME}/server.js"), Map.of("BASE", "${HOME}"));
        McpServerConfig expanded = McpConfigLoader.expandConfig(config);
        assertFalse(expanded.command().contains("${HOME}"));
    }

    @Test
    void expandConfig_expandsSseFields() {
        McpServerConfig config = McpServerConfig.sse("test",
                "http://${VERY_UNLIKELY_VAR_XYZ_123:-localhost}:3000",
                Map.of("Auth", "${VERY_UNLIKELY_VAR_XYZ_123:-default}"));
        McpServerConfig expanded = McpConfigLoader.expandConfig(config);
        assertTrue(expanded.url().contains("localhost"));
        assertEquals("default", expanded.headers().get("Auth"));
    }
}
