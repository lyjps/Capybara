package com.co.claudecode.demo.message;

/**
 * 工具引用块 — 对应 Anthropic API 的 {@code tool_reference} 内容类型。
 * <p>
 * 由 {@code ToolSearchTool} 在 tool_result 中返回，表示"已发现某工具"。
 * 后续 {@code extractDiscoveredToolNames()} 会扫描消息历史中的此类型块，
 * 以确定哪些延迟工具已被发现、需要发送 schema。
 * <p>
 * 对应 TS 的 {@code { type: 'tool_reference', tool_name: string }} 结构。
 *
 * @param toolName 被引用的工具名称
 */
public record ToolReferenceBlock(String toolName) implements ContentBlock {

    public ToolReferenceBlock {
        toolName = toolName == null ? "" : toolName;
    }

    @Override
    public String renderForModel() {
        return "[tool_reference] " + toolName;
    }
}
