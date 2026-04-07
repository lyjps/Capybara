package com.co.claudecode.demo.mcp;

/**
 * 从 MCP 服务器发现的资源信息。
 * <p>
 * 对应 MCP resources/list 响应中的单个资源描述。
 *
 * @param uri         资源 URI
 * @param name        资源名称
 * @param mimeType    MIME 类型（可选）
 * @param description 资源描述（可选）
 */
public record McpResourceInfo(String uri, String name, String mimeType, String description) {
}
