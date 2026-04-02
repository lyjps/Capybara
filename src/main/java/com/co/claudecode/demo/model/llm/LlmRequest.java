package com.co.claudecode.demo.model.llm;

import java.util.List;

/**
 * 发往 provider 的标准化请求。
 * tools 携带可用工具的 schema，让模型知道能调用什么。
 */
public record LlmRequest(
        String systemPrompt,
        List<LlmMessage> messages,
        String modelName,
        List<ToolSchema> tools
) {

    public LlmRequest {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /**
     * 工具 schema，传入 Anthropic / OpenAI 的 tools 字段。
     *
     * @param name        工具名（如 list_files）
     * @param description 工具描述
     * @param parameters  参数列表（名字 -> 描述）
     */
    public record ToolSchema(String name, String description, List<ParamSchema> parameters) {

        public ToolSchema {
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
        }

        public record ParamSchema(String name, String description, boolean required) {
        }
    }
}
