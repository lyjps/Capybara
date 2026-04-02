package com.co.claudecode.demo.model.llm;

/**
 * 流式输出回调。收到文本 token 时立即通知调用方（如打印到终端）。
 */
@FunctionalInterface
public interface StreamCallback {

    /**
     * 收到一个文本片段时调用。
     * 实现方通常直接 print 到终端，不换行。
     */
    void onTextToken(String token);
}
