package com.co.claudecode.demo.agent;

import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.message.ToolCallBlock;
import com.co.claudecode.demo.model.ModelAdapter;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolOrchestrator;

import java.util.List;
import java.util.function.Consumer;

/**
 * 这里刻意把主循环写得很显式。
 * 对 agent 系统来说，最值得读清楚的不是某个工具细节，而是
 * “消息 -> 工具 -> 消息 -> 下一轮”这条闭环到底如何成立。
 */
public final class AgentEngine {

    private final ConversationMemory memory;
    private final ModelAdapter modelAdapter;
    private final ToolOrchestrator toolOrchestrator;
    private final ToolExecutionContext context;
    private final int maxTurns;

    public AgentEngine(ConversationMemory memory,
                       ModelAdapter modelAdapter,
                       ToolOrchestrator toolOrchestrator,
                       ToolExecutionContext context,
                       int maxTurns) {
        this.memory = memory;
        this.modelAdapter = modelAdapter;
        this.toolOrchestrator = toolOrchestrator;
        this.context = context;
        this.maxTurns = maxTurns;
    }

    public ConversationMessage run(String userGoal, Consumer<String> eventSink) {
        memory.append(ConversationMessage.user(userGoal));
        eventSink.accept("USER  > " + userGoal);

        for (int turn = 1; turn <= maxTurns; turn++) {
            eventSink.accept("\nTURN  > " + turn);

            ConversationMessage assistantMessage = modelAdapter.nextReply(memory.snapshot(), context);
            boolean compactedAfterAssistant = memory.append(assistantMessage);
            eventSink.accept("ASSIST > " + assistantMessage.plainText());
            if (compactedAfterAssistant) {
                eventSink.accept("STATE > 历史已 compact，下一轮将读取压缩后的上下文。");
            }

            List<ToolCallBlock> toolCalls = assistantMessage.toolCalls();
            if (toolCalls.isEmpty()) {
                return assistantMessage;
            }

            List<ConversationMessage> toolResults = toolOrchestrator.execute(toolCalls, context, eventSink);
            for (ConversationMessage toolResult : toolResults) {
                boolean compactedAfterTool = memory.append(toolResult);
                eventSink.accept("RESULT> " + toolResult.renderForModel());
                if (compactedAfterTool) {
                    eventSink.accept("STATE > 工具结果已压缩进历史摘要。");
                }
            }
        }

        throw new IllegalStateException("超过最大轮数，说明 demo 规划器没有按预期收敛。");
    }
}
