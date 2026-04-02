package com.co.claudecode.demo.model.llm;

import java.util.List;
import java.util.Map;

/**
 * provider 返回的标准化响应。
 * 既能承载纯文本回复，也能承载 tool_use 调用。
 */
public record LlmResponse(String text, List<ToolCallData> toolCalls) {

    public LlmResponse {
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** 纯文本便捷构造。 */
    public LlmResponse(String text) {
        this(text, List.of());
    }

    /**
     * 模型返回的单个工具调用。
     */
    public record ToolCallData(String id, String name, Map<String, String> input) {

        public ToolCallData {
            id = id == null ? "" : id;
            name = name == null ? "" : name;
            input = input == null ? Map.of() : Map.copyOf(input);
        }
    }
}
