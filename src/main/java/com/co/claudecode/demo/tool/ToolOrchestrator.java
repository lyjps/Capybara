package com.co.claudecode.demo.tool;

import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.message.ToolCallBlock;
import com.co.claudecode.demo.message.ToolResultBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * 原项目里并不是“所有工具都并发”，而是只让安全的那部分并发。
 * 这里沿用同样的思路，因为真正昂贵的 bug 往往来自顺序被偷换，
 * 而不是来自少跑了几个并发。
 */
public final class ToolOrchestrator implements AutoCloseable {

    private final ToolRegistry toolRegistry;
    private final PermissionPolicy permissionPolicy;
    private final ExecutorService executorService;

    /**
     * 当前会话消息快照（用于 buildSchemaNotSentHint 判断）。
     * 在每次 execute 调用前由外部设置。
     */
    private volatile List<ConversationMessage> currentConversation = List.of();

    public ToolOrchestrator(ToolRegistry toolRegistry, PermissionPolicy permissionPolicy, int maxConcurrency) {
        this.toolRegistry = toolRegistry;
        this.permissionPolicy = permissionPolicy;
        this.executorService = Executors.newFixedThreadPool(maxConcurrency);
    }

    /**
     * 设置当前会话消息快照，用于 schema-not-sent 提示判断。
     */
    public void setCurrentConversation(List<ConversationMessage> conversation) {
        this.currentConversation = conversation != null ? conversation : List.of();
    }

    public List<ConversationMessage> execute(List<ToolCallBlock> calls,
                                             ToolExecutionContext context,
                                             Consumer<String> eventSink) {
        List<ConversationMessage> results = new ArrayList<>();
        for (Batch batch : partition(calls)) {
            if (batch.concurrentSafe()) {
                results.addAll(executeConcurrent(batch.calls(), context, eventSink));
            } else {
                for (ToolCallBlock call : batch.calls()) {
                    results.add(executeSingle(call, context, eventSink));
                }
            }
        }
        return results;
    }

    private List<Batch> partition(List<ToolCallBlock> calls) {
        List<Batch> batches = new ArrayList<>();
        for (ToolCallBlock call : calls) {
            boolean concurrencySafe = toolRegistry.require(call.toolName()).metadata().concurrencySafe();
            Batch lastBatch = batches.isEmpty() ? null : batches.get(batches.size() - 1);
            if (lastBatch != null && lastBatch.concurrentSafe() == concurrencySafe && concurrencySafe) {
                lastBatch.calls().add(call);
            } else {
                List<ToolCallBlock> batchCalls = new ArrayList<>();
                batchCalls.add(call);
                batches.add(new Batch(concurrencySafe, batchCalls));
            }
        }
        return batches;
    }

    private List<ConversationMessage> executeConcurrent(List<ToolCallBlock> calls,
                                                        ToolExecutionContext context,
                                                        Consumer<String> eventSink) {
        try {
            List<Callable<ConversationMessage>> tasks = calls.stream()
                    .map(call -> (Callable<ConversationMessage>) () -> executeSingle(call, context, eventSink))
                    .toList();
            List<Future<ConversationMessage>> futures = executorService.invokeAll(tasks);
            List<ConversationMessage> messages = new ArrayList<>(futures.size());
            for (Future<ConversationMessage> future : futures) {
                messages.add(future.get());
            }
            return messages;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return List.of(errorResult("tool_batch", "并发执行被中断。"));
        } catch (ExecutionException error) {
            return List.of(errorResult("tool_batch", "并发执行失败: " + error.getCause().getMessage()));
        }
    }

    private ConversationMessage executeSingle(ToolCallBlock call,
                                              ToolExecutionContext context,
                                              Consumer<String> eventSink) {
        Tool tool;
        try {
            tool = toolRegistry.require(call.toolName());
        } catch (Exception error) {
            // 工具未注册 — 检查是否可以给出 schema-not-sent 提示
            String errorMsg = error.getMessage();
            String hint = buildSchemaNotSentHintForUnknown(call.toolName());
            if (hint != null) {
                errorMsg = (errorMsg != null ? errorMsg : "Unknown tool") + hint;
            }
            return errorResult(call.id(), errorMsg);
        }

        eventSink.accept("TOOL  > " + tool.metadata().name() + " " + summarizeInput(call.input()));

        try {
            tool.validate(call.input());

            PermissionDecision decision = permissionPolicy.evaluate(tool, call, context);
            if (!decision.allowed()) {
                return new ConversationMessage(
                        com.co.claudecode.demo.message.MessageRole.USER,
                        List.of(new ToolResultBlock(call.id(), tool.metadata().name(), true, decision.reason()))
                );
            }

            ToolResult result = tool.execute(call.input(), context);
            // 执行失败时追加 schema-not-sent 提示
            if (result.error()) {
                String hint = ToolSearchUtils.buildSchemaNotSentHint(
                        tool, currentConversation, toolRegistry.allTools());
                if (hint != null) {
                    return ConversationMessage.toolResult(
                            new ToolResultBlock(call.id(), tool.metadata().name(), true,
                                    result.content() + hint)
                    );
                }
            }
            return ConversationMessage.toolResult(
                    new ToolResultBlock(call.id(), tool.metadata().name(), result.error(), result.content())
            );
        } catch (Exception error) {
            // 执行异常时追加 schema-not-sent 提示
            String errorMsg = error.getMessage() != null ? error.getMessage() : "执行异常";
            String hint = ToolSearchUtils.buildSchemaNotSentHint(
                    tool, currentConversation, toolRegistry.allTools());
            if (hint != null) {
                errorMsg += hint;
            }
            return errorResult(call.id(), errorMsg);
        }
    }

    /**
     * 执行单个工具调用（公开接口，供 {@code StreamingToolExecutor} 使用）。
     * <p>
     * 与内部 {@code executeSingle()} 相同逻辑，但提供 public 可见性，
     * 使得流式工具执行器可以单独调度每个工具的执行。
     *
     * @param call      工具调用块
     * @param context   工具执行上下文
     * @param eventSink 事件输出回调
     * @return 工具执行结果消息
     */
    public ConversationMessage executeSingleTool(ToolCallBlock call,
                                                  ToolExecutionContext context,
                                                  Consumer<String> eventSink) {
        return executeSingle(call, context, eventSink);
    }

    /**
     * 当工具名在注册表中找不到时，检查是否工具搜索可以帮助发现它。
     * 这处理了模型"猜测"一个延迟工具名的情况。
     */
    private String buildSchemaNotSentHintForUnknown(String toolName) {
        if (!ToolSearchUtils.isToolSearchEnabledOptimistic()) {
            return null;
        }
        if (!ToolSearchUtils.isToolSearchToolAvailable(toolRegistry.allTools())) {
            return null;
        }
        return "\n\nThis tool may be available but its schema was not loaded. "
                + "Try calling " + ToolSearchUtils.TOOL_SEARCH_TOOL_NAME
                + " with query \"select:" + toolName + "\" to discover and load it.";
    }

    private ConversationMessage errorResult(String toolUseId, String message) {
        return ConversationMessage.toolResult(
                new ToolResultBlock(toolUseId, "tool_error", true, message == null ? "未知错误" : message)
        );
    }

    private String summarizeInput(Map<String, String> input) {
        if (input.isEmpty()) {
            return "{}";
        }
        return input.toString();
    }

    @Override
    public void close() {
        executorService.shutdown();
    }

    private record Batch(boolean concurrentSafe, List<ToolCallBlock> calls) {
    }
}
