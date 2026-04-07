package com.co.claudecode.demo.mcp.auth;

import com.co.claudecode.demo.mcp.protocol.SimpleJsonParser;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * JWT Bearer Token 提供器 — 实现 OAuth2 Client Credentials + Token Exchange 两步认证。
 * <p>
 * 用于美团地图 MCPHub 等需要 JWT OAuth 认证的 Streamable HTTP MCP 服务器。
 * <p>
 * 零外部依赖：HS256 JWT 使用 JDK {@code javax.crypto.Mac} 手工实现。
 * <p>
 * Token 缓存：复用 token 直到过期前 5 分钟，过期后自动刷新。
 */
public final class JwtTokenProvider {

    /** Token 提前刷新的安全边距。 */
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

    /** 默认 token 有效期（秒），当服务器未返回 expires_in 时使用。 */
    private static final long DEFAULT_EXPIRES_IN_SECONDS = 10800; // 3 hours

    /** HTTP 请求超时。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final McpAuthConfig authConfig;
    private final HttpClient httpClient;
    private final Consumer<String> logger;

    // Token 缓存
    private volatile String cachedToken;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    public JwtTokenProvider(McpAuthConfig authConfig) {
        this(authConfig, null);
    }

    public JwtTokenProvider(McpAuthConfig authConfig, Consumer<String> logger) {
        this.authConfig = authConfig;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.logger = logger != null ? logger : s -> {};
    }

    /**
     * 获取 Authorization header Map。供 StreamableHttpTransport 的 dynamicHeaders 使用。
     *
     * @return 包含 Authorization header 的 Map，token 获取失败时返回空 Map
     */
    public Map<String, String> getAuthHeaders() {
        try {
            String token = getToken();
            return Map.of("Authorization", "Bearer " + token);
        } catch (IOException e) {
            logger.accept("JWT > Failed to get token: " + e.getMessage());
            return Map.of();
        }
    }

    /**
     * 获取有效的 Bearer Token（带缓存）。
     *
     * @return access token
     * @throws IOException 如果 token 获取失败
     */
    public String getToken() throws IOException {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiry.minus(REFRESH_MARGIN))) {
            return cachedToken;
        }

        synchronized (this) {
            // 双重检查
            if (cachedToken != null && Instant.now().isBefore(tokenExpiry.minus(REFRESH_MARGIN))) {
                return cachedToken;
            }
            return refreshToken();
        }
    }

    /**
     * 强制刷新 token。
     */
    synchronized String refreshToken() throws IOException {
        logger.accept("JWT > Refreshing token...");

        // Step 1: Client Credentials Grant
        String assertion = buildJwt();
        String initialToken = clientCredentialsGrant(assertion);

        // Step 2: Token Exchange
        TokenResult result = tokenExchange(initialToken, assertion);

        cachedToken = result.accessToken;
        tokenExpiry = Instant.now().plusSeconds(result.expiresIn);
        logger.accept("JWT > Token refreshed, expires in " + result.expiresIn + "s");
        return cachedToken;
    }

    // ================================================================
    //  OAuth Steps
    // ================================================================

    /**
     * Step 1: Client Credentials Grant — 获取初始 access token。
     */
    String clientCredentialsGrant(String assertion) throws IOException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "client_credentials");
        params.put("client_id", authConfig.clientId());
        params.put("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
        params.put("client_assertion", assertion);
        params.put("client_secret", authConfig.clientSecret());

        String responseJson = postForm(authConfig.tokenEndpoint(), params);
        String accessToken = SimpleJsonParser.extractField(responseJson, "access_token");
        if (accessToken == null || accessToken.isBlank()) {
            throw new IOException("Client Credentials response missing access_token: " + responseJson);
        }
        return accessToken;
    }

    /**
     * Step 2: Token Exchange Grant — 将初始 token 交换为 MCP 服务器范围的 token。
     */
    TokenResult tokenExchange(String subjectToken, String assertion) throws IOException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange");
        params.put("requested_token_type", "urn:ietf:params:oauth:token-type:access_token");
        params.put("subject_token", subjectToken);
        params.put("subject_token_type", "urn:ietf:params:oauth:token-type:access_token");
        params.put("client_id", authConfig.clientId());
        params.put("audience", authConfig.audience());
        params.put("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
        params.put("client_assertion", assertion);
        params.put("client_secret", authConfig.clientSecret());

        String responseJson = postForm(authConfig.tokenEndpoint(), params);
        String accessToken = SimpleJsonParser.extractField(responseJson, "access_token");
        if (accessToken == null || accessToken.isBlank()) {
            throw new IOException("Token Exchange response missing access_token: " + responseJson);
        }

        String expiresInStr = SimpleJsonParser.extractField(responseJson, "expires_in");
        long expiresIn = DEFAULT_EXPIRES_IN_SECONDS;
        if (expiresInStr != null && !expiresInStr.isBlank()) {
            try {
                expiresIn = Long.parseLong(expiresInStr.trim());
            } catch (NumberFormatException e) {
                // 使用默认值
            }
        }

        return new TokenResult(accessToken, expiresIn);
    }

    // ================================================================
    //  JWT Construction (HS256, zero dependencies)
    // ================================================================

    /**
     * 构建 HS256 签名的 JWT Client Assertion。
     * <p>
     * Header: {@code {"alg":"HS256","typ":"JWT"}}
     * Payload: {@code {sub, iss, aud, exp, iat, jti}}
     * Signature: HMAC-SHA256(header.payload, clientSecret)
     */
    String buildJwt() {
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

        long now = Instant.now().getEpochSecond();
        String payload = "{\"sub\":\"" + authConfig.clientId()
                + "\",\"iss\":\"" + authConfig.clientId()
                + "\",\"aud\":\"" + authConfig.tokenEndpoint()
                + "\",\"exp\":" + (now + 86400)
                + ",\"iat\":" + now
                + ",\"jti\":\"" + UUID.randomUUID() + "\"}";

        String headerB64 = base64UrlEncode(header.getBytes(StandardCharsets.UTF_8));
        String payloadB64 = base64UrlEncode(payload.getBytes(StandardCharsets.UTF_8));
        String signInput = headerB64 + "." + payloadB64;

        byte[] signature = hmacSha256(
                signInput.getBytes(StandardCharsets.UTF_8),
                authConfig.clientSecret().getBytes(StandardCharsets.UTF_8));
        String signatureB64 = base64UrlEncode(signature);

        return signInput + "." + signatureB64;
    }

    // ================================================================
    //  Crypto Helpers
    // ================================================================

    static String base64UrlEncode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    static byte[] hmacSha256(byte[] data, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 failed", e);
        }
    }

    // ================================================================
    //  HTTP Helpers
    // ================================================================

    private String postForm(String url, Map<String, String> params) throws IOException {
        StringBuilder body = new StringBuilder();
        for (var entry : params.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                throw new IOException("OAuth token endpoint returned HTTP " + response.statusCode()
                        + ": " + response.body());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Token request interrupted", e);
        }
    }

    // ================================================================
    //  Inner Types
    // ================================================================

    record TokenResult(String accessToken, long expiresIn) {
    }
}
