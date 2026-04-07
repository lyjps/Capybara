package com.co.claudecode.demo.tool.streaming;

import com.co.claudecode.demo.message.ToolCallBlock;
import com.co.claudecode.demo.model.llm.StreamCallback;

/**
 * 扩展 {@link StreamCallback}，增加工具完成通知能力。
 * <p>
 * 对应 TS 中 SSE 流处理过程中的 tool_use block 完成回调。
 * 当 SSE 流中的 {@code content_block_stop} 事件标志一个 tool_use block 完成时，
 * {@link com.co.claudecode.demo.model.llm.AnthropicProviderClient} 会检查 callback
 * 是否是 {@code StreamingToolCallback} 的实例，如果是则调用 {@link #onToolUseComplete(ToolCallBlock)}。
 * <p>
 * 使用独立接口继承而非修改 {@link StreamCallback}，保持其 {@code @FunctionalInterface} 约定不变。
 */
public interface StreamingToolCallback extends StreamCallback {

    /**
     * 当一个 tool_use content block 在 SSE 流中完成时调用。
     * <p>
     * 调用时机：{@code content_block_stop} 事件，且 block 类型为 {@code tool_use}。
     * 调用线程：SSE 读取线程（{@code parseSseStream()} 内部）。
     * <p>
     * 典型实现：将 toolCall 转交给 {@link StreamingToolExecutor#addTool(ToolCallBlock)}，
     * 后者会立即调度执行（如果满足并发条件）。
     *
     * @param toolCall 已完成解析的工具调用块（包含 id, name, input）
     */
    void onToolUseComplete(ToolCallBlock toolCall);
}
