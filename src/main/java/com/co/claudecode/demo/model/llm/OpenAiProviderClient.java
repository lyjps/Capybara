package com.co.claudecode.demo.model.llm;

public final class OpenAiProviderClient extends AbstractLlmProviderClient {

    public OpenAiProviderClient(ModelRuntimeConfig runtimeConfig) {
        super(runtimeConfig);
    }

    @Override
    public ModelProvider provider() {
        return ModelProvider.OPENAI;
    }
}
