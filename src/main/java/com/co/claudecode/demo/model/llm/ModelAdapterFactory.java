package com.co.claudecode.demo.model.llm;

import com.co.claudecode.demo.model.ModelAdapter;
import com.co.claudecode.demo.model.RuleBasedModelAdapter;

/**
 * 入口只关心“要哪个模型后端”，不关心具体 provider 如何实例化。
 * 这样未来要接 SDK、HTTP、mock、replay，都只改工厂和 provider 层。
 */
public final class ModelAdapterFactory {

    private ModelAdapterFactory() {
    }

    public static ModelAdapter create(ModelRuntimeConfig runtimeConfig) {
        return switch (runtimeConfig.provider()) {
            case OPENAI -> new LlmBackedModelAdapter(
                    new OpenAiProviderClient(runtimeConfig),
                    new LlmConversationMapper(),
                    runtimeConfig
            );
            case ANTHROPIC -> new LlmBackedModelAdapter(
                    new AnthropicProviderClient(runtimeConfig),
                    new LlmConversationMapper(),
                    runtimeConfig
            );
            case RULES -> new RuleBasedModelAdapter();
        };
    }
}
