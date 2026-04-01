package com.co.claudecode.demo.model;

import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.tool.ToolExecutionContext;

import java.util.List;

/**
 * 这个接口存在的意义是把“如何思考”与“如何执行”拆开。
 * 原项目能同时服务 REPL、SDK、未来模式，靠的就是这条边界。
 */
public interface ModelAdapter {

    ConversationMessage nextReply(List<ConversationMessage> conversation, ToolExecutionContext context);
}
