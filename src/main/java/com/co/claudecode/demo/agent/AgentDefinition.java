package com.co.claudecode.demo.agent;

import java.util.List;

/**
 * Agent 类型定义。
 * <p>
 * 对应 TS 原版 {@code AgentDefinition} 联合类型（built-in / custom / plugin）。
 * Java 里用单一 record + source 字段区分来源。
 * <p>
 * {@code allowedTools} 为 null 表示允许所有工具（等价于 tools: ["*"]）；
 * {@code disallowedTools} 列出需要禁止的工具（即使 allowedTools=null 也生效）。
 */
public record AgentDefinition(
        String agentType,
        String whenToUse,
        Source source,
        String systemPrompt,
        List<String> allowedTools,
        List<String> disallowedTools,
        boolean readOnly,
        int maxTurns,
        String model
) {

    public enum Source {
        BUILT_IN,
        CUSTOM
    }

    public AgentDefinition {
        if (agentType == null || agentType.isBlank()) {
            throw new IllegalArgumentException("agentType must not be blank");
        }
        if (systemPrompt == null) {
            systemPrompt = "";
        }
        allowedTools = allowedTools == null ? null : List.copyOf(allowedTools);
        disallowedTools = disallowedTools == null ? List.of() : List.copyOf(disallowedTools);
        if (maxTurns <= 0) {
            maxTurns = 12;
        }
    }

    /** 内置 Agent 便捷工厂。 */
    public static AgentDefinition builtIn(String agentType,
                                          String whenToUse,
                                          String systemPrompt,
                                          List<String> allowedTools,
                                          List<String> disallowedTools,
                                          boolean readOnly,
                                          int maxTurns) {
        return new AgentDefinition(agentType, whenToUse, Source.BUILT_IN,
                systemPrompt, allowedTools, disallowedTools, readOnly, maxTurns, null);
    }

    /** 自定义 Agent 便捷工厂。 */
    public static AgentDefinition custom(String agentType,
                                         String whenToUse,
                                         String systemPrompt,
                                         List<String> allowedTools,
                                         List<String> disallowedTools,
                                         boolean readOnly,
                                         int maxTurns,
                                         String model) {
        return new AgentDefinition(agentType, whenToUse, Source.CUSTOM,
                systemPrompt, allowedTools, disallowedTools, readOnly, maxTurns, model);
    }

    /** 该工具名是否被此 Agent 允许使用。 */
    public boolean isToolAllowed(String toolName) {
        if (disallowedTools.contains(toolName)) {
            return false;
        }
        if (allowedTools == null) {
            return true; // null = wildcard ["*"]
        }
        return allowedTools.contains(toolName);
    }
}
