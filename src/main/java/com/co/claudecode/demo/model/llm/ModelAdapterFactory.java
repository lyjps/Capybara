package com.co.claudecode.demo.model.llm;

import com.co.claudecode.demo.model.ModelAdapter;
import com.co.claudecode.demo.model.RuleBasedModelAdapter;
import com.co.claudecode.demo.tool.ToolRegistry;

/**
 * 入口只关心"要哪个模型后端"，不关心具体 provider 如何实例化。
 */
public final class ModelAdapterFactory {

    private ModelAdapterFactory() {
    }

    /**
     * @param streamCallback 流式回调，传 null 则使用非流式模式
     */
    public static ModelAdapter create(ModelRuntimeConfig runtimeConfig,
                                      ToolRegistry toolRegistry,
                                      StreamCallback streamCallback) {
        return switch (runtimeConfig.provider()) {
            case OPENAI -> new LlmBackedModelAdapter(
                    new OpenAiProviderClient(runtimeConfig),
                    new LlmConversationMapper(),
                    runtimeConfig,
                    toolRegistry,
                    streamCallback
            );
            case ANTHROPIC -> new LlmBackedModelAdapter(
                    new AnthropicProviderClient(runtimeConfig),
                    new LlmConversationMapper(),
                    runtimeConfig,
                    toolRegistry,
                    streamCallback
            );
            case RULES -> new RuleBasedModelAdapter();
        };
    }

    /** 非流式便捷重载。 */
    public static ModelAdapter create(ModelRuntimeConfig runtimeConfig, ToolRegistry toolRegistry) {
        return create(runtimeConfig, toolRegistry, null);
    }
}
