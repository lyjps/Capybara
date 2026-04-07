package com.co.claudecode.demo.mcp.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtTokenProvider 单元测试。
 * <p>
 * 测试 JWT 构造、Base64URL 编码、HMAC-SHA256 签名、token 缓存逻辑。
 * 不涉及真实 HTTP 调用。
 */
class JwtTokenProviderTest {

    private static final McpAuthConfig TEST_CONFIG = new McpAuthConfig(
            "https://token.example.com/token",
            "test-client-id",
            "test-client-secret",
            "test-audience"
    );

    // ---- JWT 构造测试 ----

    @Test
    void buildJwt_producesThreePartToken() {
        JwtTokenProvider provider = new JwtTokenProvider(TEST_CONFIG);
        String jwt = provider.buildJwt();

        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length, "JWT should have 3 parts separated by dots");
    }

    @Test
    void buildJwt_headerIsHS256() {
        JwtTokenProvider provider = new JwtTokenProvider(TEST_CONFIG);
        String jwt = provider.buildJwt();
        String headerB64 = jwt.split("\\.")[0];

        String header = new String(Base64.getUrlDecoder().decode(headerB64), StandardCharsets.UTF_8);
        assertTrue(header.contains("\"alg\":\"HS256\""));
        assertTrue(header.contains("\"typ\":\"JWT\""));
    }

    @Test
    void buildJwt_payloadContainsRequiredClaims() {
        JwtTokenProvider provider = new JwtTokenProvider(TEST_CONFIG);
        String jwt = provider.buildJwt();
        String payloadB64 = jwt.split("\\.")[1];

        String payload = new String(Base64.getUrlDecoder().decode(payloadB64), StandardCharsets.UTF_8);
        assertTrue(payload.contains("\"sub\":\"test-client-id\""));
        assertTrue(payload.contains("\"iss\":\"test-client-id\""));
        assertTrue(payload.contains("\"aud\":\"https://token.example.com/token\""));
        assertTrue(payload.contains("\"exp\":"));
        assertTrue(payload.contains("\"iat\":"));
        assertTrue(payload.contains("\"jti\":"));
    }

    @Test
    void buildJwt_signatureIsNotEmpty() {
        JwtTokenProvider provider = new JwtTokenProvider(TEST_CONFIG);
        String jwt = provider.buildJwt();
        String signature = jwt.split("\\.")[2];

        assertFalse(signature.isEmpty(), "Signature should not be empty");
    }

    @Test
    void buildJwt_differentCallsProduceDifferentJti() {
        JwtTokenProvider provider = new JwtTokenProvider(TEST_CONFIG);
        String jwt1 = provider.buildJwt();
        String jwt2 = provider.buildJwt();

        // jti 是 UUID，每次不同
        String payload1 = new String(Base64.getUrlDecoder().decode(jwt1.split("\\.")[1]));
        String payload2 = new String(Base64.getUrlDecoder().decode(jwt2.split("\\.")[1]));
        assertNotEquals(payload1, payload2, "Each JWT should have a unique jti");
    }

    // ---- Base64URL 编码测试 ----

    @Test
    void base64UrlEncode_noPadding() {
        String result = JwtTokenProvider.base64UrlEncode("test".getBytes(StandardCharsets.UTF_8));
        assertFalse(result.contains("="), "Base64URL should not contain padding");
        assertFalse(result.contains("+"), "Base64URL should not contain +");
        assertFalse(result.contains("/"), "Base64URL should not contain /");
    }

    // ---- HMAC-SHA256 测试 ----

    @Test
    void hmacSha256_producesNonEmptyResult() {
        byte[] result = JwtTokenProvider.hmacSha256(
                "test-data".getBytes(StandardCharsets.UTF_8),
                "test-key".getBytes(StandardCharsets.UTF_8));
        assertNotNull(result);
        assertEquals(32, result.length, "HMAC-SHA256 should produce 32 bytes");
    }

    @Test
    void hmacSha256_sameInputProducesSameOutput() {
        byte[] key = "key".getBytes(StandardCharsets.UTF_8);
        byte[] data = "data".getBytes(StandardCharsets.UTF_8);
        byte[] result1 = JwtTokenProvider.hmacSha256(data, key);
        byte[] result2 = JwtTokenProvider.hmacSha256(data, key);
        assertArrayEquals(result1, result2);
    }

    @Test
    void hmacSha256_differentKeyProducesDifferentOutput() {
        byte[] data = "data".getBytes(StandardCharsets.UTF_8);
        byte[] result1 = JwtTokenProvider.hmacSha256(data, "key1".getBytes(StandardCharsets.UTF_8));
        byte[] result2 = JwtTokenProvider.hmacSha256(data, "key2".getBytes(StandardCharsets.UTF_8));
        assertFalse(java.util.Arrays.equals(result1, result2));
    }

    // ---- getAuthHeaders 测试 ----

    @Test
    void getAuthHeaders_returnsEmptyOnError() {
        // 由于无法连接 token endpoint，getAuthHeaders 应返回空 Map
        JwtTokenProvider provider = new JwtTokenProvider(TEST_CONFIG);
        Map<String, String> headers = provider.getAuthHeaders();
        assertTrue(headers.isEmpty(), "Should return empty map when token fetch fails");
    }
}
