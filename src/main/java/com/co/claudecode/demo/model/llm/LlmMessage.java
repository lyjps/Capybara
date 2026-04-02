package com.co.claudecode.demo.model.llm;

import com.co.claudecode.demo.message.ToolCallBlock;

import java.util.List;

/**
 * provider 通用消息格式。
 * <p>
 * 扩展为三种类型：
 * - TEXT：普通文本消息
 * - TOOL_CALLS：assistant 消息中包含工具调用
 * - TOOL_RESULT：工具执行结果回传给模型
 */
public record LlmMessage(
        String role,
        String content,
        Type type,
        // TOOL_RESULT 专用字段
        String toolUseId,
        String toolName,
        boolean isError,
        // TOOL_CALLS 专用字段
        List<ToolCallBlock> toolCalls
) {

    public enum Type {
        TEXT,
        TOOL_CALLS,
        TOOL_RESULT
    }

    /** 纯文本消息。 */
    public LlmMessage(String role, String content) {
        this(role, content, Type.TEXT, null, null, false, List.of());
    }

    /** tool_result 消息。 */
    public LlmMessage(String role, String content, Type type,
                       String toolUseId, String toolName, boolean isError) {
        this(role, content, type, toolUseId, toolName, isError, List.of());
    }

    /** assistant 消息中包含 tool_calls。 */
    public LlmMessage(String role, String content, Type type,
                       List<ToolCallBlock> toolCalls) {
        this(role, content, type, null, null, false,
                toolCalls == null ? List.of() : List.copyOf(toolCalls));
    }
}
