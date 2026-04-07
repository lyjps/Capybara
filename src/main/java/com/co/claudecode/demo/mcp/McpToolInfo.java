package com.co.claudecode.demo.mcp;

import java.util.List;

/**
 * 从 MCP 服务器发现的工具信息。
 * <p>
 * 对应 MCP tools/list 响应中的单个工具定义。
 *
 * @param name            工具名称（原始名，不含 mcp__ 前缀）
 * @param description     工具描述
 * @param inputSchemaJson 原始 JSON schema 字符串
 * @param params          解析后的参数列表（用于 ToolMetadata.ParamInfo 映射）
 * @param readOnly        是否只读（从 annotations.readOnlyHint 获取）
 */
public record McpToolInfo(
        String name,
        String description,
        String inputSchemaJson,
        List<McpParamInfo> params,
        boolean readOnly
) {

    public McpToolInfo {
        params = params != null ? List.copyOf(params) : List.of();
    }

    /**
     * MCP 工具参数信息。
     *
     * @param name        参数名
     * @param type        参数类型（string, number, boolean, object, array）
     * @param description 参数描述
     * @param required    是否必填
     */
    public record McpParamInfo(String name, String type, String description, boolean required) {
    }
}
