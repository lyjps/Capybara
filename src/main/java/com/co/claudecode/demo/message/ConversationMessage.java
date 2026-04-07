package com.co.claudecode.demo.message;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 即使 block 已经结构化了，role 依然保留。
 * 原因不是重复建模，而是 compact、规则模型和审计都需要知道
 * “这段信息是谁说的”，否则同一段文本的决策权重会被误判。
 */
public record ConversationMessage(MessageRole role, List<ContentBlock> blocks) {

    public ConversationMessage {
        role = role == null ? MessageRole.USER : role;
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    public static ConversationMessage system(String text) {
        return new ConversationMessage(MessageRole.SYSTEM, List.of(new TextBlock(text)));
    }

    public static ConversationMessage user(String text) {
        return new ConversationMessage(MessageRole.USER, List.of(new TextBlock(text)));
    }

    public static ConversationMessage assistant(String text, List<ToolCallBlock> toolCalls) {
        List<ContentBlock> result = new ArrayList<>();
        if (text != null && !text.isBlank()) {
            result.add(new TextBlock(text));
        }
        if (toolCalls != null) {
            result.addAll(toolCalls);
        }
        return new ConversationMessage(MessageRole.ASSISTANT, result);
    }

    public static ConversationMessage toolResult(ToolResultBlock toolResultBlock) {
        return new ConversationMessage(MessageRole.USER, List.of(toolResultBlock));
    }

    public List<ToolCallBlock> toolCalls() {
        return blocks.stream()
                .filter(ToolCallBlock.class::isInstance)
                .map(ToolCallBlock.class::cast)
                .toList();
    }

    public List<ToolResultBlock> toolResults() {
        return blocks.stream()
                .filter(ToolResultBlock.class::isInstance)
                .map(ToolResultBlock.class::cast)
                .toList();
    }

    /**
     * 提取消息中的工具引用块（由 ToolSearchTool 返回）。
     */
    public List<ToolReferenceBlock> toolReferences() {
        return blocks.stream()
                .filter(ToolReferenceBlock.class::isInstance)
                .map(ToolReferenceBlock.class::cast)
                .toList();
    }

    public String plainText() {
        return blocks.stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::text)
                .collect(Collectors.joining("\n"))
                .trim();
    }

    public String renderForModel() {
        String content = blocks.stream()
                .map(ContentBlock::renderForModel)
                .collect(Collectors.joining("\n"));
        return role.label() + ": " + content;
    }
}
