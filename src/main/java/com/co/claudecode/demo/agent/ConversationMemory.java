package com.co.claudecode.demo.agent;

import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.message.MessageRole;

import java.util.ArrayList;
import java.util.List;

/**
 * 原项目里系统提示、历史消息、compact 结果都要进入同一个上下文窗口。
 * 这里故意不做"聊天记录"和"摘要记录"双存储，目的是让下一轮看到的
 * 始终是最终上下文，而不是某种需要额外拼装的派生视图。
 * <p>
 * 注意：compact 时必须确保 tool_use 和 tool_result 配对完整。
 * Anthropic API 要求每个 tool_result 的 tool_use_id 必须对应前一条
 * assistant 消息中的某个 tool_use block，否则返回 400。
 */
public final class ConversationMemory {

    private final List<ConversationMessage> messages = new ArrayList<>();
    private final SimpleContextCompactor compactor;
    private final int maxMutableMessagesBeforeCompact;
    private final int preservedTailSize;

    public ConversationMemory(SimpleContextCompactor compactor,
                              int maxMutableMessagesBeforeCompact,
                              int preservedTailSize) {
        this.compactor = compactor;
        this.maxMutableMessagesBeforeCompact = maxMutableMessagesBeforeCompact;
        this.preservedTailSize = preservedTailSize;
    }

    public boolean append(ConversationMessage message) {
        messages.add(message);
        return maybeCompact();
    }

    public List<ConversationMessage> snapshot() {
        return List.copyOf(messages);
    }

    private boolean maybeCompact() {
        int protectedPrefix = hasSystemHeader() ? 1 : 0;
        int mutableSize = messages.size() - protectedPrefix;
        if (mutableSize <= maxMutableMessagesBeforeCompact) {
            return false;
        }

        int tailStart = Math.max(protectedPrefix, messages.size() - preservedTailSize);
        if (tailStart <= protectedPrefix) {
            return false;
        }

        // 关键修正：向前扩展 tailStart 直到它不会把 tool_use/tool_result 对拆开。
        // 如果 tailStart 位置的消息是 tool_result（USER 角色带 ToolResultBlock），
        // 说明对应的 assistant（含 tool_use）在 tailStart 之前，会被压缩掉。
        // 必须把 tailStart 向前移到那条 assistant 消息。
        tailStart = adjustTailForToolPairing(protectedPrefix, tailStart);

        if (tailStart <= protectedPrefix) {
            return false;
        }

        List<ConversationMessage> compactedPart = new ArrayList<>(messages.subList(protectedPrefix, tailStart));
        if (compactedPart.isEmpty()) {
            return false;
        }

        ConversationMessage summary = compactor.compact(compactedPart);

        List<ConversationMessage> next = new ArrayList<>();
        next.addAll(messages.subList(0, protectedPrefix));
        next.add(summary);
        next.addAll(messages.subList(tailStart, messages.size()));

        messages.clear();
        messages.addAll(next);
        return true;
    }

    /**
     * 向前调整 tailStart，确保不会把 assistant（含 tool_use）压缩掉
     * 而把对应的 tool_result 留在 tail 中。
     * <p>
     * 规则：如果 tail 开头的消息包含 tool_result，就往前找到它对应的
     * assistant 消息（包含 tool_use），把切分点移到那条 assistant 之前。
     */
    private int adjustTailForToolPairing(int protectedPrefix, int tailStart) {
        while (tailStart > protectedPrefix) {
            ConversationMessage atTail = messages.get(tailStart);
            // 如果这条消息包含 tool_result，它的前一条应该是 assistant with tool_use
            if (!atTail.toolResults().isEmpty()) {
                // 往前找对应的 assistant（通常就是前一条或前几条 tool_result 的共同 assistant）
                int search = tailStart - 1;
                while (search >= protectedPrefix) {
                    ConversationMessage candidate = messages.get(search);
                    if (candidate.role() == MessageRole.ASSISTANT && !candidate.toolCalls().isEmpty()) {
                        tailStart = search;
                        break;
                    }
                    search--;
                }
                if (search < protectedPrefix) {
                    // 找不到匹配的 assistant，保守策略：不压缩
                    tailStart = protectedPrefix;
                }
                break;
            } else {
                break;
            }
        }
        return tailStart;
    }

    private boolean hasSystemHeader() {
        return !messages.isEmpty() && messages.get(0).role() == MessageRole.SYSTEM;
    }
}
