package com.co.claudecode.demo.mcp.auth;

/**
 * MCP 服务器 OAuth2 认证配置。
 * <p>
 * 用于 Streamable HTTP 传输的 JWT Bearer Token 认证（如美团地图 MCPHub）。
 * 支持 Client Credentials + Token Exchange 两步 OAuth 流程。
 *
 * @param tokenEndpoint OAuth token 端点
 * @param clientId      客户端 ID
 * @param clientSecret  客户端密钥（也用作 HS256 JWT 签名密钥）
 * @param audience      Token Exchange 目标受众（targetMcpServerId）
 */
public record McpAuthConfig(
        String tokenEndpoint,
        String clientId,
        String clientSecret,
        String audience
) {

    /**
     * 基本验证：所有字段不能为空。
     */
    public boolean isValid() {
        return tokenEndpoint != null && !tokenEndpoint.isBlank()
                && clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank()
                && audience != null && !audience.isBlank();
    }
}
