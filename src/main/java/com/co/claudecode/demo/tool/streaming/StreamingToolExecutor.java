package com.co.claudecode.demo.tool.streaming;

import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.message.ToolCallBlock;
import com.co.claudecode.demo.tool.Tool;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolOrchestrator;
import com.co.claudecode.demo.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 流式工具执行器 — 在 SSE 流输出过程中立即调度已完成的 tool_use block。
 * <p>
 * 对应 TS {@code StreamingToolExecutor.ts} 的核心逻辑。
 * <p>
 * 生命周期：每个 agent loop turn 创建一个新实例，SSE 流结束后通过
 * {@link #awaitAllResults()} 收集所有结果，然后该实例不再使用。
 * <p>
 * 线程安全：
 * <ul>
 *   <li>{@link #addTool(ToolCallBlock)} 由 SSE 读取线程调用</li>
 *   <li>{@link #processQueue()} 由 SSE 读取线程和线程池工作线程调用</li>
 *   <li>{@link #awaitAllResults()} 由 agent loop 主线程调用</li>
 *   <li>所有调度状态变更在 {@code schedulingLock} 内完成</li>
 * </ul>
 */
public final class StreamingToolExecutor implements AutoCloseable {

    // ================================================================
    //  状态机
    // ================================================================

    /**
     * 工具执行状态。
     * <p>
     * 对应 TS TrackedTool.status。
     */
    public enum ToolStatus {
        /** 已入队，等待调度。 */
        QUEUED,
        /** 正在执行。 */
        EXECUTING,
        /** 执行成功完成。 */
        COMPLETED,
        /** 执行失败。 */
        FAILED
    }

    /**
     * 被跟踪的工具实例。
     * <p>
     * 对应 TS TrackedTool 类型。使用 mutable class 而非 record，因为 status 需要在
     * synchronized 块内变更。
     */
    static final class TrackedTool {
        final ToolCallBlock block;
        final boolean isConcurrencySafe;
        final CompletableFuture<ConversationMessage> future;
        volatile ToolStatus status;
        volatile ConversationMessage result;

        TrackedTool(ToolCallBlock block, boolean isConcurrencySafe) {
            this.block = block;
            this.isConcurrencySafe = isConcurrencySafe;
            this.future = new CompletableFuture<>();
            this.status = ToolStatus.QUEUED;
        }
    }

    // ================================================================
    //  字段
    // ================================================================

    /** 按添加顺序保存所有被跟踪的工具。 */
    private final CopyOnWriteArrayList<TrackedTool> tools = new CopyOnWriteArrayList<>();

    /** 调度锁 — 保护 activeConcurrentCount 和 exclusiveActive。 */
    private final Object schedulingLock = new Object();

    /** 当前正在并行执行的 concurrent-safe 工具数量。 */
    private int activeConcurrentCount = 0;

    /** 是否有一个独占（非 concurrent-safe）工具正在执行。 */
    private boolean exclusiveActive = false;

    /** 是否已被废弃（SSE 流失败时调用 discard）。 */
    private volatile boolean discarded = false;

    // 依赖
    private final ToolOrchestrator orchestrator;
    private final ToolRegistry toolRegistry;
    private final ToolExecutionContext context;
    private final Consumer<String> eventSink;
    private final ExecutorService pool;

    /**
     * 创建流式工具执行器。
     *
     * @param orchestrator 工具编排器（复用其权限检查和执行逻辑）
     * @param toolRegistry 工具注册表（用于查找 concurrencySafe 属性）
     * @param context      工具执行上下文
     * @param eventSink    事件输出（日志）
     * @param poolSize     线程池大小
     */
    public StreamingToolExecutor(ToolOrchestrator orchestrator,
                                  ToolRegistry toolRegistry,
                                  ToolExecutionContext context,
                                  Consumer<String> eventSink,
                                  int poolSize) {
        this.orchestrator = orchestrator;
        this.toolRegistry = toolRegistry;
        this.context = context;
        this.eventSink = eventSink;
        this.pool = Executors.newFixedThreadPool(poolSize);
    }

    // ================================================================
    //  核心 API
    // ================================================================

    /**
     * 添加一个已完成解析的 tool_use block 到执行队列。
     * <p>
     * 由 SSE 读取线程调用（通过 {@link StreamingToolCallback#onToolUseComplete(ToolCallBlock)}）。
     * 添加后立即尝试调度执行。
     * <p>
     * 对应 TS {@code addTool(toolUse, assistantMessage)}。
     *
     * @param block 已完成的工具调用块
     */
    public void addTool(ToolCallBlock block) {
        if (discarded) {
            return;
        }

        boolean concurrencySafe = lookupConcurrencySafe(block.toolName());
        TrackedTool tracked = new TrackedTool(block, concurrencySafe);
        tools.add(tracked);

        eventSink.accept("STREAM > queued tool: " + block.toolName()
                + " (concurrent=" + concurrencySafe + ")");

        processQueue();
    }

    /**
     * 调度队列中的待执行工具。
     * <p>
     * 对应 TS {@code processQueue()}。
     * 在 schedulingLock 内遍历所有 QUEUED 工具，满足条件则提交到线程池。
     * <p>
     * 调用时机：
     * <ul>
     *   <li>{@link #addTool(ToolCallBlock)} — SSE 读取线程</li>
     *   <li>工具执行完成后的 finally 块 — 线程池工作线程</li>
     * </ul>
     */
    void processQueue() {
        if (discarded) {
            return;
        }

        synchronized (schedulingLock) {
            for (TrackedTool tool : tools) {
                if (tool.status != ToolStatus.QUEUED) {
                    continue;
                }
                if (!canExecute(tool)) {
                    // 遇到不能执行的工具就停止（保序调度）
                    // 非 concurrent-safe 工具需要等所有前面的完成
                    if (!tool.isConcurrencySafe) {
                        break;
                    }
                    continue;
                }

                tool.status = ToolStatus.EXECUTING;
                if (tool.isConcurrencySafe) {
                    activeConcurrentCount++;
                } else {
                    exclusiveActive = true;
                }

                // 提交到线程池
                final TrackedTool t = tool;
                pool.submit(() -> executeTool(t));
            }
        }
    }

    /**
     * 判断一个工具当前是否可以执行。
     * <p>
     * 对应 TS {@code canExecuteTool(tool)}。
     * <p>
     * 规则：
     * <ul>
     *   <li>独占工具正在执行 → 任何工具都不能执行</li>
     *   <li>concurrent-safe 工具 → 只要没有独占工具在执行就可以</li>
     *   <li>非 concurrent-safe 工具 → 没有任何工具在执行时才可以</li>
     * </ul>
     * <p>
     * 必须在 schedulingLock 内调用。
     */
    private boolean canExecute(TrackedTool tool) {
        if (exclusiveActive) {
            return false;
        }
        if (tool.isConcurrencySafe) {
            return true; // 没有独占工具就可以并行
        }
        // 非 concurrent-safe：必须没有任何工具在执行
        return activeConcurrentCount == 0;
    }

    /**
     * 在线程池中执行一个工具。
     * <p>
     * 对应 TS {@code executeTool(tool)}。
     * 执行完成后更新状态和计数器，然后触发下一轮调度。
     */
    private void executeTool(TrackedTool tool) {
        try {
            eventSink.accept("STREAM > executing: " + tool.block.toolName());
            ConversationMessage result = orchestrator.executeSingleTool(
                    tool.block, context, eventSink);
            tool.result = result;
            tool.status = ToolStatus.COMPLETED;
            tool.future.complete(result);
        } catch (Exception e) {
            tool.status = ToolStatus.FAILED;
            ConversationMessage errorResult = ConversationMessage.toolResult(
                    new com.co.claudecode.demo.message.ToolResultBlock(
                            tool.block.id(), tool.block.toolName(), true,
                            e.getMessage() != null ? e.getMessage() : "Streaming execution error"
                    )
            );
            tool.result = errorResult;
            tool.future.complete(errorResult);
        } finally {
            // 更新调度计数器
            synchronized (schedulingLock) {
                if (tool.isConcurrencySafe) {
                    activeConcurrentCount--;
                } else {
                    exclusiveActive = false;
                }
            }
            // Fire-and-forget：触发下一轮调度
            processQueue();
        }
    }

    /**
     * 等待所有工具执行完成，按插入顺序收集结果。
     * <p>
     * 对应 TS {@code getRemainingResults()}。
     * 由 agent loop 主线程在 SSE 流结束后调用。
     *
     * @return 按工具添加顺序排列的所有工具执行结果
     */
    public List<ConversationMessage> awaitAllResults() {
        List<ConversationMessage> results = new ArrayList<>(tools.size());
        for (TrackedTool tool : tools) {
            try {
                results.add(tool.future.join());
            } catch (Exception e) {
                // CompletableFuture.join() 不该抛异常（我们总是 complete 不是 completeExceptionally）
                // 但安全起见兜底
                results.add(ConversationMessage.toolResult(
                        new com.co.claudecode.demo.message.ToolResultBlock(
                                tool.block.id(), tool.block.toolName(), true,
                                "Unexpected error awaiting result: " + e.getMessage()
                        )
                ));
            }
        }
        return results;
    }

    /**
     * 废弃当前执行器（SSE 流失败需要重试时调用）。
     * <p>
     * 对应 TS {@code discard()}。
     * 标记为已废弃，取消所有未完成的 future，不再接受新工具。
     */
    public void discard() {
        discarded = true;
        for (TrackedTool tool : tools) {
            if (tool.status == ToolStatus.QUEUED) {
                tool.status = ToolStatus.FAILED;
                tool.future.complete(ConversationMessage.toolResult(
                        new com.co.claudecode.demo.message.ToolResultBlock(
                                tool.block.id(), tool.block.toolName(), true,
                                "Discarded: SSE stream retry"
                        )
                ));
            }
        }
    }

    /**
     * 检查是否有任何工具被添加过。
     *
     * @return true 如果至少有一个工具被添加到执行器
     */
    public boolean hasTools() {
        return !tools.isEmpty();
    }

    /**
     * 获取当前跟踪的工具数量（用于测试和调试）。
     */
    public int toolCount() {
        return tools.size();
    }

    /**
     * 检查是否已被废弃。
     */
    public boolean isDiscarded() {
        return discarded;
    }

    @Override
    public void close() {
        pool.shutdown();
    }

    // ================================================================
    //  内部辅助
    // ================================================================

    /**
     * 查找工具的 concurrencySafe 属性。
     * 如果工具未注册，默认视为非 concurrent-safe（保守策略）。
     */
    private boolean lookupConcurrencySafe(String toolName) {
        try {
            Tool tool = toolRegistry.require(toolName);
            return tool.metadata().concurrencySafe();
        } catch (Exception e) {
            // 未注册的工具，保守处理
            return false;
        }
    }
}
