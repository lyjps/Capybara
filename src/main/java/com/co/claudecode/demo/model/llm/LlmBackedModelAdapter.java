package com.co.claudecode.demo.model.llm;

import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.model.ModelAdapter;
import com.co.claudecode.demo.tool.ToolExecutionContext;

import java.util.List;

/**
 * 让 provider adapter 也实现同一个 ModelAdapter，是为了保证 agent loop
 * 对“规则模型”和“真实模型”一视同仁。模型后端变化不应该迫使 AgentEngine 分叉。
 */
public final class LlmBackedModelAdapter implements ModelAdapter {

    private final LlmProviderClient providerClient;
    private final LlmConversationMapper mapper;
    private final ModelRuntimeConfig runtimeConfig;

    public LlmBackedModelAdapter(LlmProviderClient providerClient,
                                 LlmConversationMapper mapper,
                                 ModelRuntimeConfig runtimeConfig) {
        this.providerClient = providerClient;
        this.mapper = mapper;
        this.runtimeConfig = runtimeConfig;
    }

    @Override
    public ConversationMessage nextReply(List<ConversationMessage> conversation, ToolExecutionContext context) {
        LlmRequest request = mapper.toRequest(conversation, runtimeConfig.modelName());
        LlmResponse response = providerClient.generate(request);
        return ConversationMessage.assistant(
                response.text(),
                List.of()
        );
    }
}
