package com.co.claudecode.demo.tool;

import java.util.List;

/**
 * 工具元数据。
 * <p>
 * 延迟加载（Deferred Loading）字段说明：
 * <ul>
 *   <li>{@code isMcp} — 是否为 MCP 外部工具。MCP 工具默认被延迟加载。</li>
 *   <li>{@code shouldDefer} — 显式标记为应延迟加载。</li>
 *   <li>{@code alwaysLoad} — 显式标记为始终加载（永不延迟），优先级最高。</li>
 *   <li>{@code searchHint} — 供 ToolSearch 关键词匹配的额外提示词（逗号分隔）。</li>
 * </ul>
 * <p>
 * 延迟加载判定优先级：alwaysLoad → isMcp → ToolSearch 本身 → shouldDefer。
 * 对应 TS {@code isDeferredTool()} 的逻辑。
 */
public record ToolMetadata(String name,
                           String description,
                           boolean readOnly,
                           boolean concurrencySafe,
                           boolean destructive,
                           PathDomain pathDomain,
                           String pathInputKey,
                           List<ParamInfo> params,
                           boolean isMcp,
                           boolean shouldDefer,
                           boolean alwaysLoad,
                           String searchHint) {

    public ToolMetadata {
        params = params == null ? List.of() : List.copyOf(params);
        searchHint = searchHint == null ? "" : searchHint;
    }

    /**
     * 兼容旧构造（不带 params 和 deferred loading 字段）。
     */
    public ToolMetadata(String name, String description, boolean readOnly,
                        boolean concurrencySafe, boolean destructive,
                        PathDomain pathDomain, String pathInputKey) {
        this(name, description, readOnly, concurrencySafe, destructive,
                pathDomain, pathInputKey, List.of(),
                false, false, false, "");
    }

    /**
     * 兼容旧构造（带 params，不带 deferred loading 字段）。
     */
    public ToolMetadata(String name, String description, boolean readOnly,
                        boolean concurrencySafe, boolean destructive,
                        PathDomain pathDomain, String pathInputKey,
                        List<ParamInfo> params) {
        this(name, description, readOnly, concurrencySafe, destructive,
                pathDomain, pathInputKey, params,
                false, false, false, "");
    }

    public enum PathDomain {
        NONE,
        WORKSPACE,
        ARTIFACT
    }

    /**
     * 工具参数描述，用于生成发送给模型的 JSON schema。
     */
    public record ParamInfo(String name, String description, boolean required) {
    }
}
