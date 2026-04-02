package com.co.claudecode.demo.agent;

/**
 * Agent 执行结果。
 * <p>
 * 对应 TS 原版 {@code AgentToolResult} 中的 completed 状态返回值。
 */
public record AgentResult(
        String agentId,
        String agentType,
        Status status,
        String content,
        int totalToolUseCount,
        long totalDurationMs,
        int totalTokens
) {
    public enum Status {
        COMPLETED,
        FAILED,
        KILLED
    }

    public static AgentResult completed(String agentId, String agentType,
                                        String content, int toolUseCount,
                                        long durationMs, int tokens) {
        return new AgentResult(agentId, agentType, Status.COMPLETED,
                content, toolUseCount, durationMs, tokens);
    }

    public static AgentResult failed(String agentId, String agentType,
                                     String errorMessage, long durationMs) {
        return new AgentResult(agentId, agentType, Status.FAILED,
                errorMessage, 0, durationMs, 0);
    }
}
