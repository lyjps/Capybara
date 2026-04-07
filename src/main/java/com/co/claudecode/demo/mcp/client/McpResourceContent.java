package com.co.claudecode.demo.mcp.client;

/**
 * MCP 资源读取结果。
 *
 * @param uri      资源 URI
 * @param mimeType MIME 类型
 * @param text     文本内容（对于文本资源）
 * @param isBinary 是否为二进制内容（Java demo 中不保存 blob，仅标记）
 */
public record McpResourceContent(String uri, String mimeType, String text, boolean isBinary) {
}
