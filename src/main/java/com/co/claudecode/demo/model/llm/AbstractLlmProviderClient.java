package com.co.claudecode.demo.model.llm;

/**
 * 这一层先把 provider 接入骨架定住，但故意不在当前提交里完成真实网络调用。
 * 原因是当前仓库的重点仍然是 agent architecture；如果现在直接塞满 SDK，
 * 反而会让真正稳定的边界被某个瞬时 API 形态掩盖掉。
 */
public abstract class AbstractLlmProviderClient implements LlmProviderClient {

    private final ModelRuntimeConfig runtimeConfig;

    protected AbstractLlmProviderClient(ModelRuntimeConfig runtimeConfig) {
        this.runtimeConfig = runtimeConfig;
    }

    protected ModelRuntimeConfig runtimeConfig() {
        return runtimeConfig;
    }

    @Override
    public boolean isConfigured() {
        return runtimeConfig.apiKey() != null && !runtimeConfig.apiKey().isBlank();
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        String message = """
                Provider skeleton is active.
                provider=%s
                model=%s
                baseUrl=%s

                This adapter intentionally stops before real network invocation.
                The stable part for this repository is the abstraction boundary:
                request normalization, provider selection, and response handoff.
                """.formatted(provider(), runtimeConfig.modelName(), runtimeConfig.baseUrl());
        return new LlmResponse(message.trim());
    }
}
