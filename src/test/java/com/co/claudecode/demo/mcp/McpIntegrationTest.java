package com.co.claudecode.demo.mcp;

import com.co.claudecode.demo.mcp.client.McpClient;
import com.co.claudecode.demo.mcp.client.McpConnectionManager;
import com.co.claudecode.demo.mcp.client.McpInitResult;
import com.co.claudecode.demo.mcp.protocol.JsonRpcError;
import com.co.claudecode.demo.mcp.protocol.JsonRpcRequest;
import com.co.claudecode.demo.mcp.protocol.JsonRpcResponse;
import com.co.claudecode.demo.mcp.protocol.SimpleJsonParser;
import com.co.claudecode.demo.mcp.tool.ListMcpResourcesTool;
import com.co.claudecode.demo.mcp.tool.McpToolBridge;
import com.co.claudecode.demo.mcp.tool.ReadMcpResourceTool;
import com.co.claudecode.demo.mcp.transport.McpTransport;
import com.co.claudecode.demo.tool.Tool;
import com.co.claudecode.demo.tool.ToolMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP 端到端集成测试。
 * <p>
 * 验证：配置加载 → 命名 → JSON-RPC → 工具桥接 的完整流程。
 */
class McpIntegrationTest {

    @TempDir
    Path tempDir;

    // ---- Config → Name → Parse roundtrip ----

    @Test
    void configToName_roundtrip() {
        // 1. 创建配置
        McpServerConfig config = McpServerConfig.stdio("my-test.server", "node",
                List.of("server.js"), Map.of());

        // 2. 规范化名称
        String normalized = McpNameUtils.normalizeForMcp(config.name());
        assertEquals("my-test_server", normalized);

        // 3. 构建工具名
        String toolName = McpNameUtils.buildToolName(config.name(), "get_file");
        assertEquals("mcp__my-test_server__get_file", toolName);

        // 4. 解析工具名
        McpNameUtils.McpToolRef ref = McpNameUtils.parseToolName(toolName);
        assertNotNull(ref);
        assertEquals("my-test_server", ref.serverName());
        assertEquals("get_file", ref.toolName());
    }

    // ---- JSON roundtrip ----

    @Test
    void jsonRpc_requestResponseRoundtrip() {
        // 构建请求
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "test-tool");
        params.put("arguments", new SimpleJsonParser.RawJson("{\"path\":\"/test\"}"));
        String paramsJson = SimpleJsonParser.toJsonObject(params);

        JsonRpcRequest request = JsonRpcRequest.of("42", "tools/call", paramsJson);
        String requestJson = request.toJson();

        // 验证请求格式
        assertTrue(requestJson.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(requestJson.contains("\"id\":\"42\""));
        assertTrue(requestJson.contains("\"method\":\"tools/call\""));

        // 模拟响应解析
        String responseJson = "{\"jsonrpc\":\"2.0\",\"id\":\"42\","
                + "\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"OK\"}]}}";
        JsonRpcResponse response = JsonRpcResponse.parse(responseJson);
        assertFalse(response.isError());
        assertEquals("42", response.id());
    }

    // ---- Config file loading ----

    @Test
    void configFile_loadAndExpand() throws IOException {
        String json = """
                {
                  "mcpServers": {
                    "local-server": {
                      "command": "${HOME}/bin/server",
                      "args": ["--port", "${PORT:-8080}"]
                    },
                    "remote-server": {
                      "type": "sse",
                      "url": "http://${HOST:-localhost}:3000/mcp"
                    }
                  }
                }
                """;
        Files.writeString(tempDir.resolve(".mcp.json"), json);

        List<McpServerConfig> configs = McpConfigLoader.loadConfigs(tempDir);
        assertEquals(2, configs.size());

        // stdio config should have HOME expanded
        McpServerConfig local = configs.stream()
                .filter(c -> c.name().equals("local-server")).findFirst().orElseThrow();
        assertFalse(local.command().contains("${HOME}"));
        assertTrue(local.args().contains("8080")); // default value

        // sse config
        McpServerConfig remote = configs.stream()
                .filter(c -> c.name().equals("remote-server")).findFirst().orElseThrow();
        assertEquals(McpTransportType.SSE, remote.transportType());
        assertTrue(remote.url().contains("localhost"));
    }

    // ---- McpClient with mock transport ----

    @Test
    void mcpClient_initializeAndListTools() throws IOException {
        MockTransport transport = new MockTransport(List.of(
                // initialize response
                "{\"serverInfo\":{\"name\":\"test-srv\",\"version\":\"2.0\"},"
                        + "\"capabilities\":{\"tools\":{},\"resources\":{}}}",
                // tools/list response
                "{\"tools\":[{\"name\":\"search\",\"description\":\"Search files\","
                        + "\"inputSchema\":{\"type\":\"object\","
                        + "\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"Search query\"}},"
                        + "\"required\":[\"query\"]},"
                        + "\"annotations\":{\"readOnlyHint\":true}}]}"
        ));

        McpClient client = new McpClient(transport, "test");
        McpInitResult init = client.initialize();
        assertEquals("test-srv", init.serverName());
        assertTrue(init.supportsTools());

        List<McpToolInfo> tools = client.listTools();
        assertEquals(1, tools.size());
        assertEquals("search", tools.get(0).name());
        assertTrue(tools.get(0).readOnly());
        assertEquals(1, tools.get(0).params().size());
        assertEquals("query", tools.get(0).params().get(0).name());
        assertTrue(tools.get(0).params().get(0).required());
    }

    // ---- Tool bridge metadata ----

    @Test
    void toolBridge_metadataIntegration() {
        McpToolInfo toolInfo = new McpToolInfo("get_data", "Get data from API",
                "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\",\"description\":\"ID\"}}}",
                List.of(new McpToolInfo.McpParamInfo("id", "string", "ID", true)),
                true);

        McpConnectionManager mgr = new McpConnectionManager();
        McpToolBridge bridge = new McpToolBridge("api-server", toolInfo, mgr);

        assertEquals("mcp__api-server__get_data", bridge.metadata().name());
        assertEquals("Get data from API", bridge.metadata().description());
        assertTrue(bridge.metadata().readOnly());
        assertTrue(bridge.metadata().concurrencySafe());
        assertFalse(bridge.metadata().destructive());
        assertEquals(ToolMetadata.PathDomain.NONE, bridge.metadata().pathDomain());
        assertEquals(1, bridge.metadata().params().size());
        assertEquals("id", bridge.metadata().params().get(0).name());
        assertTrue(bridge.metadata().params().get(0).required());

        mgr.close();
    }

    // ---- createAll from manager ----

    @Test
    void createAll_fromEmptyManager() {
        McpConnectionManager mgr = new McpConnectionManager();
        List<Tool> tools = McpToolBridge.createAll(mgr);
        assertTrue(tools.isEmpty());
        mgr.close();
    }

    // ---- Permission policy integration ----

    @Test
    void permissionPolicy_mcpToolPattern() {
        McpPermissionPolicy policy = McpPermissionPolicy.allowAll(
                (tool, call, context) -> com.co.claudecode.demo.tool.PermissionDecision.allow());

        // MCP tool should be allowed
        var call = new com.co.claudecode.demo.message.ToolCallBlock(
                "tc1", "mcp__server__tool", Map.of());
        assertTrue(policy.evaluate(dummyTool("mcp__server__tool"), call,
                new com.co.claudecode.demo.tool.ToolExecutionContext(Path.of("."), Path.of("out")))
                .allowed());

        // Non-MCP tool delegates to inner policy
        var call2 = new com.co.claudecode.demo.message.ToolCallBlock(
                "tc2", "read_file", Map.of());
        assertTrue(policy.evaluate(dummyTool("read_file"), call2,
                new com.co.claudecode.demo.tool.ToolExecutionContext(Path.of("."), Path.of("out")))
                .allowed());
    }

    // ---- Resource tools existence check ----

    @Test
    void resourceTools_haveCorrectMetadata() {
        McpConnectionManager mgr = new McpConnectionManager();
        ListMcpResourcesTool listTool = new ListMcpResourcesTool(mgr);
        ReadMcpResourceTool readTool = new ReadMcpResourceTool(mgr);

        assertEquals("mcp_list_resources", listTool.metadata().name());
        assertEquals("mcp_read_resource", readTool.metadata().name());
        assertTrue(listTool.metadata().readOnly());
        assertTrue(readTool.metadata().readOnly());

        mgr.close();
    }

    // ---- Connection state machine ----

    @Test
    void connectionState_lifecycle() {
        McpServerConfig config = McpServerConfig.stdio("test", "node", List.of(), Map.of());
        McpServerConnection conn = new McpServerConnection("test", config);

        // Initial state
        assertEquals(McpConnectionState.PENDING, conn.state());

        // Connect
        McpInitResult init = new McpInitResult("s", "1.0", null, true, false, false);
        conn.markConnected(null, init);
        assertEquals(McpConnectionState.CONNECTED, conn.state());

        // Fail
        conn.markFailed("connection lost");
        assertEquals(McpConnectionState.FAILED, conn.state());

        // Reconnect attempt
        conn.markPending();
        assertEquals(McpConnectionState.PENDING, conn.state());
        assertEquals(1, conn.reconnectAttempt());

        // Reconnect success resets counter
        conn.markConnected(null, init);
        assertEquals(0, conn.reconnectAttempt());

        // Disable
        conn.markDisabled();
        assertEquals(McpConnectionState.DISABLED, conn.state());
    }

    // ========== Helpers ==========

    private Tool dummyTool(String name) {
        return new Tool() {
            @Override
            public ToolMetadata metadata() {
                return new ToolMetadata(name, "dummy", true, true, false,
                        ToolMetadata.PathDomain.NONE, null, List.of());
            }

            @Override
            public com.co.claudecode.demo.tool.ToolResult execute(
                    Map<String, String> input,
                    com.co.claudecode.demo.tool.ToolExecutionContext context) {
                return new com.co.claudecode.demo.tool.ToolResult(false, "ok");
            }
        };
    }

    private static class MockTransport implements McpTransport {
        private final List<String> responses;
        private int index = 0;
        private boolean open = true;

        MockTransport(List<String> resultJsons) {
            this.responses = new ArrayList<>();
            for (String r : resultJsons) {
                responses.add("{\"jsonrpc\":\"2.0\",\"id\":\"X\",\"result\":" + r + "}");
            }
        }

        @Override public void start() { open = true; }

        @Override
        public JsonRpcResponse sendRequest(JsonRpcRequest request) {
            if (index < responses.size()) {
                String raw = responses.get(index++);
                raw = raw.replace("\"id\":\"X\"", "\"id\":\"" + request.id() + "\"");
                return JsonRpcResponse.parse(raw);
            }
            return JsonRpcResponse.error(request.id(),
                    new JsonRpcError(JsonRpcError.INTERNAL_ERROR, "No mock response", null));
        }

        @Override public void sendNotification(String method, String params) {}
        @Override public boolean isOpen() { return open; }
        @Override public void close() { open = false; }
    }
}
