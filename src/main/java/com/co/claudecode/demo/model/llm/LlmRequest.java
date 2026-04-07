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
     * @param name         工具名（如 list_files）
     * @param description  工具描述
     * @param parameters   参数列表（名字 -> 描述）
     * @param deferLoading 是否延迟加载此工具的 schema（对应 Anthropic beta tool_search 功能）。
     *                     为 true 时，provider 可在请求中标记 {@code cache_control / deferLoading}，
     *                     Anthropic 不会将此工具的完整 schema 发送给模型，直到 ToolSearchTool 发现它。
     */
    public record ToolSchema(String name, String description,
                             List<ParamSchema> parameters, boolean deferLoading) {

        public ToolSchema {
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
        }

        /** 兼容旧构造（默认 deferLoading=false）。 */
        public ToolSchema(String name, String description, List<ParamSchema> parameters) {
            this(name, description, parameters, false);
        }

        public record ParamSchema(String name, String description, boolean required) {
        }
    }
}
