package com.co.claudecode.demo.mcp;

/**
 * MCP 工具命名工具类。
 * <p>
 * 对应 TS 版 mcpStringUtils.ts + normalization.ts。
 * MCP 工具名采用 {@code mcp__<serverName>__<toolName>} 格式，
 * 用双下划线分隔服务器名和工具名。
 */
public final class McpNameUtils {

    /** MCP 工具名前缀。 */
    public static final String MCP_PREFIX = "mcp__";

    /** 双下划线分隔符。 */
    public static final String SEPARATOR = "__";

    private McpNameUtils() {
    }

    /**
     * 规范化名称以满足 MCP 命名要求。
     * <p>
     * 将 {@code [^a-zA-Z0-9_-]} 字符替换为 {@code _}。
     * 对应 TS 的 {@code normalizeNameForMCP()} 函数。
     *
     * @param name 原始名称
     * @return 规范化后的名称
     */
    public static String normalizeForMcp(String name) {
        if (name == null || name.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        // 截断到 64 字符
        String result = sb.toString();
        if (result.length() > 64) {
            result = result.substring(0, 64);
        }
        return result;
    }

    /**
     * 构建完整的 MCP 工具名。
     *
     * @param serverName 服务器名称（将被规范化）
     * @param toolName   工具名称（将被规范化）
     * @return {@code mcp__<normalizedServer>__<normalizedTool>}
     */
    public static String buildToolName(String serverName, String toolName) {
        return MCP_PREFIX + normalizeForMcp(serverName) + SEPARATOR + normalizeForMcp(toolName);
    }

    /**
     * 获取指定服务器的 MCP 工具名前缀。
     *
     * @param serverName 服务器名称
     * @return {@code mcp__<normalizedServer>__}
     */
    public static String getMcpPrefix(String serverName) {
        return MCP_PREFIX + normalizeForMcp(serverName) + SEPARATOR;
    }

    /**
     * 解析完整的 MCP 工具名。
     *
     * @param fullName 完整工具名（如 {@code mcp__server__tool}）
     * @return 解析结果，如果格式不匹配返回 null
     */
    public static McpToolRef parseToolName(String fullName) {
        if (fullName == null || !fullName.startsWith(MCP_PREFIX)) return null;

        String withoutPrefix = fullName.substring(MCP_PREFIX.length());
        int sepIdx = withoutPrefix.indexOf(SEPARATOR);
        if (sepIdx <= 0) return null;

        String serverName = withoutPrefix.substring(0, sepIdx);
        String toolName = withoutPrefix.substring(sepIdx + SEPARATOR.length());
        if (toolName.isEmpty()) return null;

        return new McpToolRef(serverName, toolName);
    }

    /**
     * 检查工具名是否为 MCP 工具。
     */
    public static boolean isMcpTool(String toolName) {
        return toolName != null && toolName.startsWith(MCP_PREFIX);
    }

    /**
     * 检查工具名是否匹配指定服务器的 MCP 工具。
     *
     * @param toolName   工具名
     * @param serverName 服务器名
     */
    public static boolean isToolFromServer(String toolName, String serverName) {
        return toolName != null && toolName.startsWith(getMcpPrefix(serverName));
    }

    /**
     * 生成用户友好的显示名称。
     */
    public static String displayName(String serverName, String toolName) {
        return serverName + " - " + toolName + " (MCP)";
    }

    /**
     * MCP 工具名解析结果。
     *
     * @param serverName 规范化后的服务器名
     * @param toolName   规范化后的工具名
     */
    public record McpToolRef(String serverName, String toolName) {
    }
}
