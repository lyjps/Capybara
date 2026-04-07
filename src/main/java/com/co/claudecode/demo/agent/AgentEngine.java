package com.co.claudecode.demo.agent;

import com.co.claudecode.demo.compact.CompactResult;
import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.message.ToolCallBlock;
import com.co.claudecode.demo.model.ModelAdapter;
import com.co.claudecode.demo.model.llm.StreamCallback;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolOrchestrator;
import com.co.claudecode.demo.tool.ToolRegistry;
import com.co.claudecode.demo.tool.streaming.StreamingToolCallback;
import com.co.claudecode.demo.tool.streaming.StreamingToolConfig;
import com.co.claudecode.demo.tool.streaming.StreamingToolExecutor;

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

    // 流式工具执行字段
    private final StreamCallback streamCallback;
    private final ToolRegistry toolRegistry;

    /** 当前轮次的流式工具执行器（每轮重置）。 */
    private volatile StreamingToolExecutor streamingToolExecutor;

    /** 主 Agent 构造（向后兼容，无流式工具执行）。 */
    public AgentEngine(ConversationMemory memory,
                       ModelAdapter modelAdapter,
                       ToolOrchestrator toolOrchestrator,
                       ToolExecutionContext context,
                       int maxTurns) {
        this(memory, modelAdapter, toolOrchestrator, context, maxTurns, null, null, null, null);
    }

    /** 子 Agent 构造（带 agentId 和 AgentTask 消息队列，无流式工具执行）。 */
    public AgentEngine(ConversationMemory memory,
                       ModelAdapter modelAdapter,
                       ToolOrchestrator toolOrchestrator,
                       ToolExecutionContext context,
                       int maxTurns,
                       String agentId,
                       AgentTask agentTask) {
        this(memory, modelAdapter, toolOrchestrator, context, maxTurns, agentId, agentTask, null, null);
    }

    /**
     * 完整构造（支持流式工具执行）。
     *
     * @param streamCallback 流式回调（用于构建 StreamingToolCallback）
     * @param toolRegistry   工具注册表（用于查找 concurrencySafe）
     */
    public AgentEngine(ConversationMemory memory,
                       ModelAdapter modelAdapter,
                       ToolOrchestrator toolOrchestrator,
                       ToolExecutionContext context,
                       int maxTurns,
                       String agentId,
                       AgentTask agentTask,
                       StreamCallback streamCallback,
                       ToolRegistry toolRegistry) {
        this.memory = memory;
        this.modelAdapter = modelAdapter;
        this.toolOrchestrator = toolOrchestrator;
        this.context = context;
        this.maxTurns = maxTurns;
        this.agentId = agentId;
        this.agentTask = agentTask;
        this.streamCallback = streamCallback;
        this.toolRegistry = toolRegistry;
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
        // 判断是否启用流式工具执行
        boolean streamingEnabled = isStreamingToolExecutionEnabled();

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

            // 流式 vs 经典 模型调用
            ConversationMessage assistantMessage;
            if (streamingEnabled) {
                assistantMessage = callModelWithStreamingTools(eventSink);
            } else {
                assistantMessage = modelAdapter.nextReply(memory.snapshot(), context);
            }

            CompactResult compactAfterAssistant = memory.appendAndCompact(assistantMessage);
            eventSink.accept("ASSIST > " + assistantMessage.plainText());
            logCompactEvent(compactAfterAssistant, eventSink);

            List<ToolCallBlock> toolCalls = assistantMessage.toolCalls();
            if (toolCalls.isEmpty()) {
                // 清理流式执行器（如果没有工具调用但执行器可能已存在）
                cleanupStreamingExecutor();
                return assistantMessage;
            }

            // 收集工具执行结果
            List<ConversationMessage> toolResults;
            if (streamingToolExecutor != null && streamingToolExecutor.hasTools()) {
                // 流式路径：工具已在 SSE 流中开始执行，等待所有结果
                eventSink.accept("STREAM > awaiting " + streamingToolExecutor.toolCount()
                        + " streamed tool results...");
                toolResults = streamingToolExecutor.awaitAllResults();
                cleanupStreamingExecutor();
            } else {
                // 经典路径：SSE 结束后批量执行
                toolOrchestrator.setCurrentConversation(memory.snapshot());
                toolResults = toolOrchestrator.execute(toolCalls, context, eventSink);
            }

            for (ConversationMessage toolResult : toolResults) {
                CompactResult compactAfterTool = memory.appendAndCompact(toolResult);
                logCompactEvent(compactAfterTool, eventSink);
            }
        }

        // 交互式模式下不抛异常，返回兜底
        ConversationMessage fallback = ConversationMessage.assistant(
                "（已达到最大推理轮数，请继续提问或缩小问题范围）", List.of());
        memory.append(fallback);
        return fallback;
    }

    /**
     * 使用流式工具回调调用模型。
     * <p>
     * 创建 StreamingToolExecutor 和 StreamingToolCallback，
     * 将 callback 传给 modelAdapter.nextReply() 的新重载。
     * SSE 流中每个 tool_use block 完成时，callback 会通知 executor 立即调度执行。
     */
    private ConversationMessage callModelWithStreamingTools(Consumer<String> eventSink) {
        // 创建本轮的流式执行器
        StreamingToolExecutor executor = new StreamingToolExecutor(
                toolOrchestrator, toolRegistry, context, eventSink, 4);
        this.streamingToolExecutor = executor;

        // 设置会话快照（用于 schema-not-sent 提示）
        toolOrchestrator.setCurrentConversation(memory.snapshot());

        // 构建复合回调：既打印文本 token，又通知工具完成
        StreamingToolCallback toolCallback = new StreamingToolCallback() {
            @Override
            public void onTextToken(String token) {
                if (streamCallback != null) {
                    streamCallback.onTextToken(token);
                }
            }

            @Override
            public void onToolUseComplete(com.co.claudecode.demo.message.ToolCallBlock toolCall) {
                executor.addTool(toolCall);
            }
        };

        return modelAdapter.nextReply(memory.snapshot(), context, toolCallback);
    }

    /**
     * 判断是否应启用流式工具执行。
     */
    private boolean isStreamingToolExecutionEnabled() {
        if (streamCallback == null || toolRegistry == null) {
            return false;
        }
        return StreamingToolConfig.isEnabled(streamCallback, toolRegistry.size());
    }

    /**
     * 清理流式工具执行器。
     */
    private void cleanupStreamingExecutor() {
        if (streamingToolExecutor != null) {
            streamingToolExecutor.close();
            streamingToolExecutor = null;
        }
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

    /**
     * 输出压缩事件日志（详细：类型 + Token 节省量）。
     */
    private void logCompactEvent(CompactResult result, Consumer<String> eventSink) {
        if (result != null && result.didCompact()) {
            eventSink.accept("COMPACT > type=" + result.type()
                    + ", removed=" + result.messagesRemoved()
                    + ", saved=" + result.tokensSaved() + " tokens"
                    + " (" + result.summary() + ")");
        }
    }

    /**
     * 获取 ConversationMemory 引用（用于外部状态查询）。
     */
    public ConversationMemory getMemory() {
        return memory;
    }
}
