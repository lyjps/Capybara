package com.co.claudecode.demo.agent;

import com.co.claudecode.demo.message.ContentBlock;
import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.message.MessageRole;
import com.co.claudecode.demo.message.SummaryBlock;
import com.co.claudecode.demo.message.TextBlock;
import com.co.claudecode.demo.message.ToolCallBlock;
import com.co.claudecode.demo.message.ToolResultBlock;

import java.util.ArrayList;
import java.util.List;

/**
 * demo 不追求“总结得多聪明”，只追求把 compact 的意图保留下来：
 * 旧历史不能无限膨胀，但历史里做过什么决策又不能被直接丢掉。
 */
public final class SimpleContextCompactor {

    public ConversationMessage compact(List<ConversationMessage> messages) {
        List<String> lines = new ArrayList<>();
        int limit = Math.min(messages.size(), 10);
        for (int index = 0; index < limit; index++) {
            ConversationMessage message = messages.get(index);
            String rendered = message.blocks().stream()
                    .map(this::condenseBlock)
                    .filter(value -> !value.isBlank())
                    .reduce((left, right) -> left + " | " + right)
                    .orElse("");
            if (!rendered.isBlank()) {
                lines.add(message.role().label() + ": " + shorten(rendered, 140));
            }
        }
        if (messages.size() > limit) {
            lines.add("...其余较早历史已折叠，只保留决策轨迹。");
        }
        return new ConversationMessage(
                MessageRole.SYSTEM,
                List.of(new SummaryBlock(String.join("\n", lines)))
        );
    }

    private String condenseBlock(ContentBlock block) {
        if (block instanceof TextBlock textBlock) {
            return textBlock.text().replace('\n', ' ').trim();
        }
        if (block instanceof SummaryBlock summaryBlock) {
            return "summary=" + summaryBlock.summary().replace('\n', ' ').trim();
        }
        if (block instanceof ToolCallBlock toolCallBlock) {
            return "tool_call=" + toolCallBlock.toolName() + toolCallBlock.input();
        }
        if (block instanceof ToolResultBlock toolResultBlock) {
            return "tool_result=" + toolResultBlock.toolName() + ":" + shorten(toolResultBlock.content(), 80);
        }
        return "";
    }

    private String shorten(String value, int maxLength) {
        String normalized = value == null ? "" : value.replace('\n', ' ').trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }
}
