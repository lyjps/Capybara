package com.co.claudecode.demo.model.llm;

import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.message.ToolCallBlock;
import com.co.claudecode.demo.model.ModelAdapter;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolRegistry;

import java.util.List;

/**
 * 让 provider adapter 也实现同一个 ModelAdapter，是为了保证 agent loop
 * 对"规则模型"和"真实模型"一视同仁。
 */
public final class LlmBackedModelAdapter implements ModelAdapter {

    private final LlmProviderClient providerClient;
    private final LlmConversationMapper mapper;
    private final ModelRuntimeConfig runtimeConfig;
    private final ToolRegistry toolRegistry;
    private final StreamCallback streamCallback;

    public LlmBackedModelAdapter(LlmProviderClient providerClient,
                                 LlmConversationMapper mapper,
                                 ModelRuntimeConfig runtimeConfig,
                                 ToolRegistry toolRegistry,
                                 StreamCallback streamCallback) {
        this.providerClient = providerClient;
        this.mapper = mapper;
        this.runtimeConfig = runtimeConfig;
        this.toolRegistry = toolRegistry;
        this.streamCallback = streamCallback;
    }

    @Override
    public ConversationMessage nextReply(List<ConversationMessage> conversation, ToolExecutionContext context) {
        LlmRequest request = mapper.toRequest(conversation, runtimeConfig.modelName(),
                toolRegistry.allTools());

        LlmResponse response;
        if (streamCallback != null) {
            response = providerClient.generateStream(request, streamCallback);
        } else {
            response = providerClient.generate(request);
        }

        List<ToolCallBlock> toolCalls = response.toolCalls().stream()
                .map(tc -> new ToolCallBlock(tc.id(), tc.name(), tc.input()))
                .toList();

        return ConversationMessage.assistant(response.text(), toolCalls);
    }
}
