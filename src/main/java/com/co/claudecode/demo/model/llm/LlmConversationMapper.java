package com.co.claudecode.demo.model.llm;

import com.co.claudecode.demo.message.ConversationMessage;

import java.util.List;

/**
 * 映射层单独抽出来，是为了隔离“仓库内部消息协议”和“provider 输入协议”。
 * 将来哪怕 tool schema、thinking、attachments 继续变复杂，也不需要改 provider 选择逻辑。
 */
public final class LlmConversationMapper {

    public LlmRequest toRequest(List<ConversationMessage> conversation, String modelName) {
        String systemPrompt = conversation.stream()
                .filter(message -> message.role().name().equals("SYSTEM"))
                .map(ConversationMessage::renderForModel)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("You are an agentic coding assistant.");

        List<LlmMessage> messages = conversation.stream()
                .filter(message -> !message.role().name().equals("SYSTEM"))
                .map(message -> new LlmMessage(message.role().name().toLowerCase(), message.renderForModel()))
                .toList();

        return new LlmRequest(systemPrompt, messages, modelName);
    }
}
