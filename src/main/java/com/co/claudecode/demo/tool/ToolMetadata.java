package com.co.claudecode.demo.tool;

public record ToolMetadata(String name,
                           String description,
                           boolean readOnly,
                           boolean concurrencySafe,
                           boolean destructive,
                           PathDomain pathDomain,
                           String pathInputKey) {

    public enum PathDomain {
        NONE,
        WORKSPACE,
        ARTIFACT
    }
}
