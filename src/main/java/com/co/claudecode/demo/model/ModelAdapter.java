package com.co.claudecode.demo.model;

import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.model.llm.StreamCallback;
import com.co.claudecode.demo.tool.ToolExecutionContext;

import java.util.List;

/**
 * 这个接口存在的意义是把”如何思考”与”如何执行”拆开。
 * 原项目能同时服务 REPL、SDK、未来模式，靠的就是这条边界。
 */
public interface ModelAdapter {

    ConversationMessage nextReply(List<ConversationMessage> conversation, ToolExecutionContext context);

    /**
     * 带流式回调的重载 — 支持在 SSE 流中使用自定义回调（如 StreamingToolCallback）。
     * <p>
     * 当流式工具执行启用时，{@code AgentEngine} 会传入包装了
     * {@link com.co.claudecode.demo.tool.streaming.StreamingToolCallback} 的回调，
     * 使得 tool_use block 在 SSE 流中完成时立即开始执行。
     * <p>
     * 默认实现忽略 callback 参数，委托到原方法（向后兼容）。
     *
     * @param conversation 会话消息列表
     * @param context      工具执行上下文
     * @param callback     流式回调（可能是 StreamingToolCallback）
     * @return 模型回复消息
     */
    default ConversationMessage nextReply(List<ConversationMessage> conversation,
                                          ToolExecutionContext context,
                                          StreamCallback callback) {
        return nextReply(conversation, context);
    }
}
