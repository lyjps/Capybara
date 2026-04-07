package com.co.claudecode.demo.mcp.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JsonRpcRequest + JsonRpcResponse 单元测试。
 */
class JsonRpcMessageTest {

    // ---- JsonRpcRequest ----

    @Test
    void request_toJson_withParams() {
        JsonRpcRequest req = JsonRpcRequest.of("1", "tools/list", "{\"cursor\":null}");
        String json = req.toJson();
        assertTrue(json.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(json.contains("\"id\":\"1\""));
        assertTrue(json.contains("\"method\":\"tools/list\""));
        assertTrue(json.contains("\"params\":{\"cursor\":null}"));
    }

    @Test
    void request_toJson_withoutParams() {
        JsonRpcRequest req = JsonRpcRequest.of("2", "notifications/initialized");
        String json = req.toJson();
        assertTrue(json.contains("\"method\":\"notifications/initialized\""));
        assertFalse(json.contains("\"params\""));
    }

    @Test
    void request_factoryMethods() {
        JsonRpcRequest r1 = JsonRpcRequest.of("a", "test");
        assertNull(r1.params());
        assertEquals("a", r1.id());
        assertEquals("test", r1.method());

        JsonRpcRequest r2 = JsonRpcRequest.of("b", "test", "{}");
        assertEquals("{}", r2.params());
    }

    // ---- JsonRpcResponse ----

    @Test
    void response_parseSuccess() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"tools\":[]}}";
        JsonRpcResponse resp = JsonRpcResponse.parse(json);
        assertEquals("1", resp.id());
        assertFalse(resp.isError());
        assertNotNull(resp.result());
        assertTrue(resp.result().contains("tools"));
    }

    @Test
    void response_parseError() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}";
        JsonRpcResponse resp = JsonRpcResponse.parse(json);
        assertTrue(resp.isError());
        assertEquals(-32601, resp.error().code());
        assertEquals("Method not found", resp.error().message());
    }

    @Test
    void response_parseEmpty_returnsError() {
        JsonRpcResponse resp = JsonRpcResponse.parse(null);
        assertTrue(resp.isError());
        assertEquals(JsonRpcError.PARSE_ERROR, resp.error().code());

        JsonRpcResponse resp2 = JsonRpcResponse.parse("");
        assertTrue(resp2.isError());
    }

    @Test
    void response_factoryMethods() {
        JsonRpcResponse success = JsonRpcResponse.success("1", "{\"ok\":true}");
        assertFalse(success.isError());
        assertEquals("{\"ok\":true}", success.result());

        JsonRpcResponse error = JsonRpcResponse.error("2",
                new JsonRpcError(-32600, "Invalid", null));
        assertTrue(error.isError());
        assertEquals(-32600, error.error().code());
    }

    // ---- JsonRpcError constants ----

    @Test
    void errorConstants_matchSpec() {
        assertEquals(-32700, JsonRpcError.PARSE_ERROR);
        assertEquals(-32600, JsonRpcError.INVALID_REQUEST);
        assertEquals(-32601, JsonRpcError.METHOD_NOT_FOUND);
        assertEquals(-32602, JsonRpcError.INVALID_PARAMS);
        assertEquals(-32603, JsonRpcError.INTERNAL_ERROR);
        assertEquals(-32000, JsonRpcError.CONNECTION_CLOSED);
        assertEquals(-32001, JsonRpcError.SESSION_EXPIRED);
    }
}
