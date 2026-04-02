package com.co.claudecode.demo.model.llm;

/**
 * provider 接入的公共基类。
 * <p>
 * 子类只需要覆写 {@link #generate(LlmRequest)} 就能接入真实网络调用。
 * 如果子类没有覆写，默认返回骨架提示信息，方便开发阶段验证 agent loop。
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

    /**
     * 默认实现：骨架模式，不发起真实网络调用。
     * 子类覆写此方法以接入真实 API。
     */
    @Override
    public LlmResponse generate(LlmRequest request) {
        String message = """
                Provider skeleton is active.
                provider=%s
                model=%s
                baseUrl=%s

                This adapter intentionally stops before real network invocation.
                Override generate() in the concrete provider to enable real calls.
                """.formatted(provider(), runtimeConfig.modelName(), runtimeConfig.baseUrl());
        return new LlmResponse(message.trim());
    }
}
