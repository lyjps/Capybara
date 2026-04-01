package com.co.claudecode.demo.model.llm;

/**
 * 这个接口不是为了抽象“HTTP 调用”本身，而是为了抽象“provider 差异”。
 * 同样的 agent loop，到了 OpenAI 和 Anthropic，真正变化的是请求组装、
 * 头部、端点和响应提取；主循环并不应该关心这些差异。
 */
public interface LlmProviderClient {

    ModelProvider provider();

    boolean isConfigured();

    LlmResponse generate(LlmRequest request);
}
