package com.co.claudecode.demo.message;

/**
 * 原项目把文本、tool_use、tool_result 都放进统一消息流里。
 * 这里保留这个设计，是为了让“模型输出”和“工具反馈”走同一条主链，
 * 避免系统状态散落在多个互不相干的对象里。
 */
public sealed interface ContentBlock permits SummaryBlock, TextBlock, ToolCallBlock, ToolResultBlock {

    String renderForModel();
}
