package com.co.claudecode.demo.agent;

import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.message.MessageRole;

import java.util.ArrayList;
import java.util.List;

/**
 * 原项目里系统提示、历史消息、compact 结果都要进入同一个上下文窗口。
 * 这里故意不做“聊天记录”和“摘要记录”双存储，目的是让下一轮看到的
 * 始终是最终上下文，而不是某种需要额外拼装的派生视图。
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

    private boolean hasSystemHeader() {
        return !messages.isEmpty() && messages.get(0).role() == MessageRole.SYSTEM;
    }
}
