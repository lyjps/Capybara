package com.co.claudecode.demo.agent;

import java.util.concurrent.CompletableFuture;

/**
 * 异步 Agent 的句柄。
 * <p>
 * 持有 agentId（立即可用）和 CompletableFuture（最终结果）。
 * 对应 TS 原版 {@code async_launched} 输出状态：调用方立即获得 agentId，
 * 后续通过 task-notification 或 TaskOutput 获取结果。
 */
public record AsyncAgentHandle(
        String agentId,
        String agentType,
        CompletableFuture<AgentResult> future
) {
}
