package com.co.claudecode.demo.model.llm;

import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.message.ToolCallBlock;
import com.co.claudecode.demo.model.ModelAdapter;
import com.co.claudecode.demo.tool.Tool;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolRegistry;
import com.co.claudecode.demo.tool.ToolSearchUtils;

import java.util.Collection;
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
        return doNextReply(conversation, context, streamCallback);
    }

    @Override
    public ConversationMessage nextReply(List<ConversationMessage> conversation,
                                          ToolExecutionContext context,
                                          StreamCallback callbackOverride) {
        // 使用传入的 callback 覆盖默认的（用于流式工具执行场景）
        StreamCallback effectiveCallback = callbackOverride != null ? callbackOverride : streamCallback;
        return doNextReply(conversation, context, effectiveCallback);
    }

    private ConversationMessage doNextReply(List<ConversationMessage> conversation,
                                             ToolExecutionContext context,
                                             StreamCallback callback) {
        Collection<Tool> allTools = toolRegistry.allTools();

        // 延迟加载：过滤工具 — 只发送非延迟工具 + 已发现的延迟工具
        List<Tool> filteredTools = ToolSearchUtils.filterToolsForApi(allTools, conversation);

        // 使用带 deferLoading 标志的重载
        LlmRequest request = mapper.toRequest(conversation, runtimeConfig.modelName(),
                filteredTools, allTools);

        LlmResponse response;
        if (callback != null) {
            response = providerClient.generateStream(request, callback);
        } else {
            response = providerClient.generate(request);
        }

        List<ToolCallBlock> toolCalls = response.toolCalls().stream()
                .map(tc -> new ToolCallBlock(tc.id(), tc.name(), tc.input()))
                .toList();

        return ConversationMessage.assistant(response.text(), toolCalls);
    }
}
