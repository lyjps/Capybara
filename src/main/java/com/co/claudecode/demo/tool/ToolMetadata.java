package com.co.claudecode.demo.tool;

import java.util.List;

public record ToolMetadata(String name,
                           String description,
                           boolean readOnly,
                           boolean concurrencySafe,
                           boolean destructive,
                           PathDomain pathDomain,
                           String pathInputKey,
                           List<ParamInfo> params) {

    public ToolMetadata {
        params = params == null ? List.of() : List.copyOf(params);
    }

    /**
     * 兼容旧构造（不带 params）。
     */
    public ToolMetadata(String name, String description, boolean readOnly,
                        boolean concurrencySafe, boolean destructive,
                        PathDomain pathDomain, String pathInputKey) {
        this(name, description, readOnly, concurrencySafe, destructive,
                pathDomain, pathInputKey, List.of());
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
