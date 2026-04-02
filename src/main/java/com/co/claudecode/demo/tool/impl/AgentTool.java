package com.co.claudecode.demo.tool.impl;

import com.co.claudecode.demo.agent.AgentDefinition;
import com.co.claudecode.demo.agent.AgentRegistry;
import com.co.claudecode.demo.agent.AgentResult;
import com.co.claudecode.demo.agent.AsyncAgentHandle;
import com.co.claudecode.demo.agent.SubAgentRunner;
import com.co.claudecode.demo.tool.Tool;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolMetadata;
import com.co.claudecode.demo.tool.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * Agent 工具——启动子 Agent 执行任务。
 * <p>
 * 对应 TS 原版 {@code Qm8}（AgentTool）核心实现。
 * 支持同步（前台）和异步（后台）两种执行模式。
 * <p>
 * 关键设计决策（与 TS 原版一致）：
 * - {@code isReadOnly() = true}：Agent 工具本身是只读的，
 *   读写行为发生在子 Agent 内部
 * - {@code isConcurrencySafe() = true}：支持并发调用
 */
public final class AgentTool implements Tool {

    private final AgentRegistry agentRegistry;
    private final SubAgentRunner subAgentRunner;

    public AgentTool(AgentRegistry agentRegistry, SubAgentRunner subAgentRunner) {
        this.agentRegistry = agentRegistry;
        this.subAgentRunner = subAgentRunner;
    }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                "agent", "Launch a sub-agent to handle complex, multi-step tasks autonomously",
                true,  // readOnly — Agent 工具本身只读，子 Agent 内部的工具控制读写
                true,  // concurrencySafe
                false,
                ToolMetadata.PathDomain.NONE, null,
                List.of(
                        new ToolMetadata.ParamInfo("description",
                                "A short (3-5 word) description of the task", true),
                        new ToolMetadata.ParamInfo("prompt",
                                "The task for the agent to perform", true),
                        new ToolMetadata.ParamInfo("subagent_type",
                                "Agent type identifier. Omit to use general-purpose", false),
                        new ToolMetadata.ParamInfo("run_in_background",
                                "Set to true to run agent in background", false),
                        new ToolMetadata.ParamInfo("name",
                                "Agent name for SendMessage addressing", false)
                ));
    }

    @Override
    public void validate(Map<String, String> input) {
        if (input.get("prompt") == null || input.get("prompt").isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
        if (input.get("description") == null || input.get("description").isBlank()) {
            throw new IllegalArgumentException("description is required");
        }
    }

    @Override
    public ToolResult execute(Map<String, String> input, ToolExecutionContext context) throws Exception {
        String prompt = input.get("prompt");
        String description = input.getOrDefault("description", "agent task");
        String subagentType = input.get("subagent_type");
        boolean runInBackground = "true".equalsIgnoreCase(input.get("run_in_background"));
        String name = input.get("name");

        // 解析 Agent 类型
        AgentDefinition agentDef;
        if (subagentType == null || subagentType.isBlank()) {
            agentDef = agentRegistry.resolve("general-purpose");
        } else {
            AgentDefinition found = agentRegistry.findOrNull(subagentType);
            if (found == null) {
                return new ToolResult(true, "Unknown agent type: " + subagentType
                        + ". Available: " + agentRegistry.allDefinitions().stream()
                        .map(AgentDefinition::agentType)
                        .toList());
            }
            agentDef = found;
        }

        // 事件回调（灰色输出子 Agent 的调试信息）
        java.util.function.Consumer<String> eventSink = event ->
                System.out.println("\u001B[90m    [" + agentDef.agentType() + "] " + event + "\u001B[0m");

        if (runInBackground) {
            // 异步执行：立即返回 agent ID
            AsyncAgentHandle handle = subAgentRunner.runAsync(agentDef, prompt, name, eventSink);
            return new ToolResult(false,
                    "{\"status\":\"async_launched\","
                            + "\"agentId\":\"" + handle.agentId() + "\","
                            + "\"description\":\"" + escapeJson(description) + "\","
                            + "\"agentType\":\"" + handle.agentType() + "\"}");
        } else {
            // 同步执行：阻塞等待完成
            AgentResult result = subAgentRunner.runSync(agentDef, prompt, name, eventSink);

            if (result.status() == AgentResult.Status.FAILED) {
                return new ToolResult(true, "Agent failed: " + result.content());
            }

            return new ToolResult(false,
                    "{\"status\":\"completed\","
                            + "\"agentId\":\"" + result.agentId() + "\","
                            + "\"agentType\":\"" + result.agentType() + "\","
                            + "\"totalDurationMs\":" + result.totalDurationMs() + ","
                            + "\"content\":\"" + escapeJson(result.content()) + "\"}");
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
