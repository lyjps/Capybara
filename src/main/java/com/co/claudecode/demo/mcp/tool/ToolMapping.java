package com.co.claudecode.demo.mcp.tool;

import java.util.List;
import java.util.Map;

/**
 * 工具名称映射规则 — 将暴露给 LLM 的工具名映射到上游 MCP 服务器的真实工具名。
 * <p>
 * 支持两种映射模式：
 * <ul>
 *   <li><b>字面映射</b>：{@code meituan_search_mix → offline_meituan_search_mix}</li>
 *   <li><b>模板映射</b>：{@code mt_map_direction → {mode}}，
 *       运行时根据参数中 mode 值替换，如 mode=driving → upstream tool name "driving"</li>
 * </ul>
 * <p>
 * 同时支持参数注入（系统默认参数）和参数删除（不发送给上游的参数）。
 *
 * @param exposedName  暴露给 LLM 的工具名
 * @param upstreamName 上游 MCP 工具名（字面映射时使用，模板映射时为 null）
 * @param template     模板字符串（如 "{mode}"、"{mode}distancematrix"），字面映射时为 null
 * @param templateKey  模板中的变量名（如 "mode"），字面映射时为 null
 * @param stripParams  转发前需要从参数中删除的 key 列表
 * @param injectParams 转发前需要注入的系统参数
 */
public record ToolMapping(
        String exposedName,
        String upstreamName,
        String template,
        String templateKey,
        List<String> stripParams,
        Map<String, Object> injectParams
) {

    public ToolMapping {
        stripParams = stripParams != null ? List.copyOf(stripParams) : List.of();
        injectParams = injectParams != null ? Map.copyOf(injectParams) : Map.of();
    }

    /**
     * 创建字面映射。
     */
    public static ToolMapping literal(String exposedName, String upstreamName) {
        return new ToolMapping(exposedName, upstreamName, null, null, List.of(), Map.of());
    }

    /**
     * 创建带参数注入和删除的字面映射。
     */
    public static ToolMapping literal(String exposedName, String upstreamName,
                                       List<String> stripParams,
                                       Map<String, Object> injectParams) {
        return new ToolMapping(exposedName, upstreamName, null, null, stripParams, injectParams);
    }

    /**
     * 创建模板映射。
     */
    public static ToolMapping template(String exposedName, String template, String templateKey) {
        return new ToolMapping(exposedName, null, template, templateKey,
                List.of(templateKey), Map.of());
    }

    /**
     * 创建带参数注入的模板映射。
     */
    public static ToolMapping template(String exposedName, String template, String templateKey,
                                        Map<String, Object> injectParams) {
        return new ToolMapping(exposedName, null, template, templateKey,
                List.of(templateKey), injectParams);
    }

    /**
     * 是否为模板映射。
     */
    public boolean isTemplate() {
        return template != null && templateKey != null;
    }

    /**
     * 解析上游工具名。
     * <p>
     * 字面映射：直接返回 {@code upstreamName}。
     * 模板映射：将模板中的 {@code {key}} 替换为参数值。
     *
     * @param params 工具调用参数
     * @return 解析后的上游工具名
     */
    public String resolveUpstreamName(Map<String, String> params) {
        if (!isTemplate()) {
            return upstreamName;
        }

        String value = params != null ? params.get(templateKey) : null;
        if (value == null || value.isBlank()) {
            // 模板变量缺失，返回模板原始字符串（让上游报错）
            return template;
        }
        return template.replace("{" + templateKey + "}", value);
    }
}
