package com.co.claudecode.demo.model.llm;

public final class AnthropicProviderClient extends AbstractLlmProviderClient {

    public AnthropicProviderClient(ModelRuntimeConfig runtimeConfig) {
        super(runtimeConfig);
    }

    @Override
    public ModelProvider provider() {
        return ModelProvider.ANTHROPIC;
    }
}
