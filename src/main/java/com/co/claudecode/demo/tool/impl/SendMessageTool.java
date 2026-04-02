package com.co.claudecode.demo.tool.impl;

import com.co.claudecode.demo.agent.AgentTaskRegistry;
import com.co.claudecode.demo.tool.Tool;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolMetadata;
import com.co.claudecode.demo.tool.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * Agent 间通信工具。
 * <p>
 * 对应 TS 原版 {@code SendMessageTool}。
 * 通过 AgentTaskRegistry 找到目标 Agent，向其 pendingMessages 队列投递消息。
 * 目标 Agent 在下一轮 executeLoop 时会消费这些消息。
 */
public final class SendMessageTool implements Tool {

    private final AgentTaskRegistry taskRegistry;

    public SendMessageTool(AgentTaskRegistry taskRegistry) {
        this.taskRegistry = taskRegistry;
    }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                "send_message",
                "Send a message to another agent by name or ID",
                false, true, false,
                ToolMetadata.PathDomain.NONE, null,
                List.of(
                        new ToolMetadata.ParamInfo("to",
                                "Recipient: agent name or agent ID", true),
                        new ToolMetadata.ParamInfo("message",
                                "The message content to send", true)
                ));
    }

    @Override
    public void validate(Map<String, String> input) {
        if (input.get("to") == null || input.get("to").isBlank()) {
            throw new IllegalArgumentException("to is required");
        }
        if (input.get("message") == null || input.get("message").isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
    }

    @Override
    public ToolResult execute(Map<String, String> input, ToolExecutionContext context) {
        String to = input.get("to");
        String message = input.get("message");

        boolean delivered = taskRegistry.sendMessage(to, message);
        if (delivered) {
            return new ToolResult(false,
                    "{\"success\":true,\"message\":\"Message queued for delivery to " + to + "\"}");
        } else {
            return new ToolResult(true,
                    "{\"success\":false,\"message\":\"Agent not found: " + to
                            + ". Available agents: " + taskRegistry.allRunning().stream()
                            .map(t -> t.name() != null ? t.name() : t.agentId())
                            .toList() + "\"}");
        }
    }
}
