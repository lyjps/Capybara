package com.co.claudecode.demo.model.llm;

/**
 * provider 专用消息格式各不相同，但语义上都绕不开 role + content。
 * 先收敛成仓库内部的统一消息，再由 provider 适配层做最后一跳转换，
 * 可以避免主循环被任意一家 API 的细节绑死。
 */
public record LlmMessage(String role, String content) {
}
