package com.co.claudecode.demo.compact;

import com.co.claudecode.demo.message.ConversationMessage;

import java.util.List;

/**
 * 压缩结果统一模型。
 * <p>
 * 所有三级压缩器返回同一种结果结构，便于上层统一处理。
 */
public record CompactResult(
        List<ConversationMessage> messages,
        CompactType type,
        int messagesRemoved,
        int tokensBeforeCompact,
        int tokensAfterCompact,
        String summary
) {
    /** 无压缩结果。 */
    public static CompactResult none(List<ConversationMessage> messages) {
        return new CompactResult(messages, CompactType.NONE, 0, 0, 0, "");
    }

    /** 压缩节省的 Token 数。 */
    public int tokensSaved() {
        return Math.max(0, tokensBeforeCompact - tokensAfterCompact);
    }

    /** 是否实际执行了压缩。 */
    public boolean didCompact() {
        return type != CompactType.NONE;
    }
}
