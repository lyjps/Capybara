package com.co.claudecode.demo.mcp.client;

import com.co.claudecode.demo.mcp.McpToolInfo;
import com.co.claudecode.demo.mcp.McpResourceInfo;
import com.co.claudecode.demo.mcp.protocol.JsonRpcError;
import com.co.claudecode.demo.mcp.protocol.JsonRpcRequest;
import com.co.claudecode.demo.mcp.protocol.JsonRpcResponse;
import com.co.claudecode.demo.mcp.transport.McpTransport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * McpClient 单元测试。
 * <p>
 * 使用 MockTransport 模拟 MCP 服务器响应。
 */
class McpClientTest {

    // ---- initialize ----

    @Test
    void initialize_success() throws IOException {
        MockTransport transport = new MockTransport();
        transport.addResponse("{\"serverInfo\":{\"name\":\"test-server\",\"version\":\"1.0.0\"},"
                + "\"capabilities\":{\"tools\":{},\"resources\":{}},\"instructions\":\"Hello\"}");

        McpClient client = new McpClient(transport, "test");
        McpInitResult result = client.initialize();

        assertEquals("test-server", result.serverName());
        assertEquals("1.0.0", result.serverVersion());
        assertEquals("Hello", result.instructions());
        assertTrue(result.supportsTools());
        assertTrue(result.supportsResources());
        assertFalse(result.supportsPrompts());
    }

    @Test
    void initialize_truncatesLongInstructions() throws IOException {
        String longInstructions = "x".repeat(3000);
        MockTransport transport = new MockTransport();
        transport.addResponse("{\"serverInfo\":{\"name\":\"s\",\"version\":\"1\"},"
                + "\"capabilities\":{},\"instructions\":\"" + longInstructions + "\"}");

        McpClient client = new McpClient(transport, "test");
        McpInitResult result = client.initialize();
        assertEquals(McpInitResult.MAX_INSTRUCTIONS_LENGTH, result.instructions().length());
    }

    @Test
    void initialize_error_throwsIOException() {
        MockTransport transport = new MockTransport();
        transport.addErrorResponse(-32603, "Internal error");

        McpClient client = new McpClient(transport, "test");
        assertThrows(IOException.class, client::initialize);
    }

    @Test
    void initialize_sendsInitializedNotification() throws IOException {
        MockTransport transport = new MockTransport();
        transport.addResponse("{\"serverInfo\":{\"name\":\"s\",\"version\":\"1\"},"
                + "\"capabilities\":{\"tools\":{}}}");

        McpClient client = new McpClient(transport, "test");
        client.initialize();

        // Verify that a notification was sent
        assertTrue(transport.notificationsSent.stream()
                .anyMatch(n -> n.contains("notifications/initialized")));
    }

    // ---- listTools ----

    @Test
    void listTools_returnsParsedTools() throws IOException {
        MockTransport transport = new MockTransport();
        transport.addResponse("{\"serverInfo\":{\"name\":\"s\",\"version\":\"1\"},\"capabilities\":{\"tools\":{}}}");
        transport.addResponse("{\"tools\":[{\"name\":\"read\",\"description\":\"Read file\","
                + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"File path\"}},\"required\":[\"path\"]},"
                + "\"annotations\":{\"readOnlyHint\":true}}]}");

        McpClient client = new McpClient(transport, "test");
        client.initialize();

        List<McpToolInfo> tools = client.listTools();
        assertEquals(1, tools.size());
        assertEquals("read", tools.get(0).name());
        assertEquals("Read file", tools.get(0).description());
        assertTrue(tools.get(0).readOnly());
        assertFalse(tools.get(0).params().isEmpty());
    }

    @Test
    void listTools_emptyList() throws IOException {
        MockTransport transport = new MockTransport();
        transport.addResponse("{\"serverInfo\":{\"name\":\"s\",\"version\":\"1\"},\"capabilities\":{\"tools\":{}}}");
        transport.addResponse("{\"tools\":[]}");

        McpClient client = new McpClient(transport, "test");
        client.initialize();
        assertTrue(client.listTools().isEmpty());
    }

    @Test
    void listTools_error_throwsIOException() throws IOException {
        MockTransport transport = new MockTransport();
        transport.addResponse("{\"serverInfo\":{\"name\":\"s\",\"version\":\"1\"},\"capabilities\":{}}");
        transport.addErrorResponse(-32601, "Method not found");

        McpClient client = new McpClient(transport, "test");
        client.initialize();
        assertThrows(IOException.class, client::listTools);
    }

    // ---- callTool ----

    @Test
    void callTool_returnsTextContent() throws IOException {
        MockTransport transport = new MockTransport();
        transport.addResponse("{\"serverInfo\":{\"name\":\"s\",\"version\":\"1\"},\"capabilities\":{}}");
        transport.addResponse("{\"content\":[{\"type\":\"text\",\"text\":\"Hello World\"}]}");

        McpClient client = new McpClient(transport, "test");
        client.initialize();

        String result = client.callTool("greet", "{\"name\":\"World\"}");
        assertEquals("Hello World", result);
    }

    @Test
    void callTool_multipleTextBlocks_concatenated() throws IOException {
        MockTransport transport = new MockTransport();
        transport.addResponse("{\"serverInfo\":{\"name\":\"s\",\"version\":\"1\"},\"capabilities\":{}}");
        transport.addResponse("{\"content\":[{\"type\":\"text\",\"text\":\"Line 1\"},{\"type\":\"text\",\"text\":\"Line 2\"}]}");

        McpClient client = new McpClient(transport, "test");
        client.initialize();

        String result = client.callTool("multi", "{}");
        assertTrue(result.contains("Line 1"));
        assertTrue(result.contains("Line 2"));
    }

    @Test
    void callTool_errorResult_throwsIOException() throws IOException {
        MockTransport transport = new MockTransport();
        transport.addResponse("{\"serverInfo\":{\"name\":\"s\",\"version\":\"1\"},\"capabilities\":{}}");
        transport.addResponse("{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"Permission denied\"}]}");

        McpClient client = new McpClient(transport, "test");
        client.initialize();
        assertThrows(IOException.class, () -> client.callTool("write", "{}"));
    }

    @Test
    void callTool_truncatesLongOutput() throws IOException {
        String longOutput = "x".repeat(200_000);
        MockTransport transport = new MockTransport();
        transport.addResponse("{\"serverInfo\":{\"name\":\"s\",\"version\":\"1\"},\"capabilities\":{}}");
        transport.addResponse("{\"content\":[{\"type\":\"text\",\"text\":\"" + longOutput + "\"}]}");

        McpClient client = new McpClient(transport, "test");
        client.initialize();

        String result = client.callTool("big", "{}");
        assertTrue(result.length() <= McpClient.MAX_OUTPUT_CHARS + 100); // +100 for truncation message
        assertTrue(result.contains("[Output truncated"));
    }

    // ---- listResources ----

    @Test
    void listResources_returnsParsedResources() throws IOException {
        MockTransport transport = new MockTransport();
        transport.addResponse("{\"serverInfo\":{\"name\":\"s\",\"version\":\"1\"},\"capabilities\":{\"resources\":{}}}");
        transport.addResponse("{\"resources\":[{\"uri\":\"file:///test.txt\",\"name\":\"test.txt\","
                + "\"mimeType\":\"text/plain\",\"description\":\"A test file\"}]}");

        McpClient client = new McpClient(transport, "test");
        client.initialize();

        List<McpResourceInfo> resources = client.listResources();
        assertEquals(1, resources.size());
        assertEquals("file:///test.txt", resources.get(0).uri());
        assertEquals("test.txt", resources.get(0).name());
        assertEquals("text/plain", resources.get(0).mimeType());
    }

    @Test
    void listResources_empty() throws IOException {
        MockTransport transport = new MockTransport();
        transport.addResponse("{\"serverInfo\":{\"name\":\"s\",\"version\":\"1\"},\"capabilities\":{\"resources\":{}}}");
        transport.addResponse("{\"resources\":[]}");

        McpClient client = new McpClient(transport, "test");
        client.initialize();
        assertTrue(client.listResources().isEmpty());
    }

    // ---- readResource ----

    @Test
    void readResource_textContent() throws IOException {
        MockTransport transport = new MockTransport();
        transport.addResponse("{\"serverInfo\":{\"name\":\"s\",\"version\":\"1\"},\"capabilities\":{\"resources\":{}}}");
        transport.addResponse("{\"contents\":[{\"uri\":\"file:///t.txt\",\"mimeType\":\"text/plain\",\"text\":\"Hello\"}]}");

        McpClient client = new McpClient(transport, "test");
        client.initialize();

        McpResourceContent content = client.readResource("file:///t.txt");
        assertEquals("file:///t.txt", content.uri());
        assertEquals("Hello", content.text());
        assertFalse(content.isBinary());
    }

    @Test
    void readResource_binaryContent() throws IOException {
        MockTransport transport = new MockTransport();
        transport.addResponse("{\"serverInfo\":{\"name\":\"s\",\"version\":\"1\"},\"capabilities\":{\"resources\":{}}}");
        transport.addResponse("{\"contents\":[{\"uri\":\"img.png\",\"mimeType\":\"image/png\","
                + "\"blob\":\"iVBORw0KGgoAAAANSUhEU\"}]}");

        McpClient client = new McpClient(transport, "test");
        client.initialize();

        McpResourceContent content = client.readResource("img.png");
        assertTrue(content.isBinary());
        assertTrue(content.text().contains("Binary content"));
    }

    // ---- close / isOpen ----

    @Test
    void close_delegatesToTransport() throws IOException {
        MockTransport transport = new MockTransport();
        McpClient client = new McpClient(transport, "test");
        assertTrue(client.isOpen());
        client.close();
        assertFalse(client.isOpen());
    }

    // ========== Mock Transport ==========

    private static class MockTransport implements McpTransport {
        private final List<String> responses = new ArrayList<>();
        private int responseIndex = 0;
        private boolean open = true;
        final List<String> notificationsSent = new ArrayList<>();

        void addResponse(String resultJson) {
            responses.add("{\"jsonrpc\":\"2.0\",\"id\":\"" + (responseIndex + 1) + "\",\"result\":" + resultJson + "}");
        }

        void addErrorResponse(int code, String message) {
            responses.add("{\"jsonrpc\":\"2.0\",\"id\":\"" + (responseIndex + 1)
                    + "\",\"error\":{\"code\":" + code + ",\"message\":\"" + message + "\"}}");
        }

        @Override
        public void start() {
            open = true;
        }

        @Override
        public JsonRpcResponse sendRequest(JsonRpcRequest request) {
            if (responseIndex < responses.size()) {
                String raw = responses.get(responseIndex++);
                // Fix id to match request
                raw = raw.replaceFirst("\"id\":\"\\d+\"", "\"id\":\"" + request.id() + "\"");
                return JsonRpcResponse.parse(raw);
            }
            return JsonRpcResponse.error(request.id(),
                    new JsonRpcError(JsonRpcError.INTERNAL_ERROR, "No more mock responses", null));
        }

        @Override
        public void sendNotification(String method, String params) {
            notificationsSent.add(method);
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }
}
