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
 * "消息 -> 工具 -> 消息 -> 下一轮"这条闭环到底如何成立。
 * <p>
 * 子 Agent 模式新增：
 * - {@code agentId} 标识当前 Agent
 * - {@code agentTask} 用于接收其他 Agent 通过 SendMessage 发来的消息
 */
public final class AgentEngine {

    private final ConversationMemory memory;
    private final ModelAdapter modelAdapter;
    private final ToolOrchestrator toolOrchestrator;
    private final ToolExecutionContext context;
    private final int maxTurns;

    // 子 Agent 系统字段（主 Agent 为 null）
    private final String agentId;
    private final AgentTask agentTask;

    /** 主 Agent 构造（向后兼容）。 */
    public AgentEngine(ConversationMemory memory,
                       ModelAdapter modelAdapter,
                       ToolOrchestrator toolOrchestrator,
                       ToolExecutionContext context,
                       int maxTurns) {
        this(memory, modelAdapter, toolOrchestrator, context, maxTurns, null, null);
    }

    /** 子 Agent 构造（带 agentId 和 AgentTask 消息队列）。 */
    public AgentEngine(ConversationMemory memory,
                       ModelAdapter modelAdapter,
                       ToolOrchestrator toolOrchestrator,
                       ToolExecutionContext context,
                       int maxTurns,
                       String agentId,
                       AgentTask agentTask) {
        this.memory = memory;
        this.modelAdapter = modelAdapter;
        this.toolOrchestrator = toolOrchestrator;
        this.context = context;
        this.maxTurns = maxTurns;
        this.agentId = agentId;
        this.agentTask = agentTask;
    }

    /**
     * 单次任务模式：传入一个 goal，跑完整个 agent loop 直到模型不再调用工具。
     */
    public ConversationMessage run(String userGoal, Consumer<String> eventSink) {
        memory.append(ConversationMessage.user(userGoal));
        eventSink.accept("USER  > " + userGoal);
        return executeLoop(eventSink);
    }

    /**
     * 交互式模式：接收一条用户输入，执行 agent loop（可能包含多轮工具调用），
     * 返回模型的最终文本回复。
     * <p>
     * 与 {@link #run(String, Consumer)} 的区别：
     * - 不抛异常，超过轮数时返回兜底回复
     * - memory 在多次 chat 调用之间共享，支持多轮上下文
     */
    public ConversationMessage chat(String userInput, Consumer<String> eventSink) {
        memory.append(ConversationMessage.user(userInput));
        return executeLoop(eventSink);
    }

    private ConversationMessage executeLoop(Consumer<String> eventSink) {
        for (int turn = 1; turn <= maxTurns; turn++) {
            // 检查取消信号（子 Agent 可被外部终止）
            if (agentTask != null && agentTask.isAborted()) {
                ConversationMessage aborted = ConversationMessage.assistant(
                        "（Agent 已被取消）", List.of());
                memory.append(aborted);
                return aborted;
            }

            // 检查并消费来自其他 Agent 的消息
            consumePendingMessages(eventSink);

            eventSink.accept("\nTURN  > " + turn);

            ConversationMessage assistantMessage = modelAdapter.nextReply(memory.snapshot(), context);
            boolean compactedAfterAssistant = memory.append(assistantMessage);
            eventSink.accept("ASSIST > " + assistantMessage.plainText());
            if (compactedAfterAssistant) {
                eventSink.accept("STATE > context compacted");
            }

            List<ToolCallBlock> toolCalls = assistantMessage.toolCalls();
            if (toolCalls.isEmpty()) {
                return assistantMessage;
            }

            List<ConversationMessage> toolResults = toolOrchestrator.execute(toolCalls, context, eventSink);
            for (ConversationMessage toolResult : toolResults) {
                boolean compactedAfterTool = memory.append(toolResult);
                if (compactedAfterTool) {
                    eventSink.accept("STATE > context compacted");
                }
            }
        }

        // 交互式模式下不抛异常，返回兜底
        ConversationMessage fallback = ConversationMessage.assistant(
                "（已达到最大推理轮数，请继续提问或缩小问题范围）", List.of());
        memory.append(fallback);
        return fallback;
    }

    /**
     * 消费来自其他 Agent 的待处理消息。
     * <p>
     * 对应 TS 原版 {@code QXK()} 消息队列消费逻辑。
     * 将消息作为 user message 注入到 Memory，供模型下一轮推理时参考。
     */
    private void consumePendingMessages(Consumer<String> eventSink) {
        if (agentTask == null || !agentTask.hasPendingMessages()) {
            return;
        }
        List<String> messages = agentTask.drainMessages();
        for (String msg : messages) {
            eventSink.accept("MSG_IN > " + msg);
            memory.append(ConversationMessage.user("[Message from another agent] " + msg));
        }
    }
}
