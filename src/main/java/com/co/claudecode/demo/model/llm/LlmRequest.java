package com.co.claudecode.demo.model.llm;

import java.util.List;

public record LlmRequest(
        String systemPrompt,
        List<LlmMessage> messages,
        String modelName
) {
}
