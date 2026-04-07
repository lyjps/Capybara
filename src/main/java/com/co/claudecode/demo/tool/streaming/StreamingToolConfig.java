package com.co.claudecode.demo.tool.streaming;

import com.co.claudecode.demo.model.llm.StreamCallback;

/**
 * 流式工具执行的 Feature Gate 配置。
 * <p>
 * 对应 TS 中 {@code ENABLE_STREAMING_TOOL_EXECUTION} 环境变量。
 * 控制是否在 SSE 流式输出过程中立即执行已完成的 tool_use block。
 * <p>
 * 三种模式：
 * <ul>
 *   <li>{@code ENABLED} — 始终启用流式工具执行</li>
 *   <li>{@code DISABLED} — 始终禁用（使用经典的 全部完成后批量执行 模式）</li>
 *   <li>{@code AUTO} — 当满足条件时自动启用（有流式回调 + 有工具注册）</li>
 * </ul>
 */
public final class StreamingToolConfig {

    /** 环境变量名。 */
    public static final String ENV_KEY = "ENABLE_STREAMING_TOOL_EXECUTION";

    /**
     * 流式工具执行模式。
     */
    public enum Mode {
        /** 始终启用。 */
        ENABLED,
        /** 始终禁用（默认）。 */
        DISABLED,
        /** 自动判断：有流式回调且有工具时启用。 */
        AUTO
    }

    private StreamingToolConfig() {
    }

    /**
     * 从环境变量解析当前模式。
     * <p>
     * 读取 {@code ENABLE_STREAMING_TOOL_EXECUTION} 环境变量：
     * <ul>
     *   <li>{@code "enabled"} / {@code "true"} → ENABLED</li>
     *   <li>{@code "auto"} → AUTO</li>
     *   <li>{@code "disabled"} / {@code "false"} / 未设置 → DISABLED</li>
     * </ul>
     */
    public static Mode getMode() {
        String envValue = System.getenv(ENV_KEY);
        if (envValue == null || envValue.isBlank()) {
            return Mode.DISABLED;
        }
        return switch (envValue.trim().toLowerCase()) {
            case "enabled", "true" -> Mode.ENABLED;
            case "auto" -> Mode.AUTO;
            case "disabled", "false" -> Mode.DISABLED;
            default -> Mode.DISABLED;
        };
    }

    /**
     * 完整检查流式工具执行是否应该启用。
     *
     * @param callback  当前使用的流式回调（可能为 null）
     * @param toolCount 注册的工具数量
     * @return true 表示应启用流式工具执行
     */
    public static boolean isEnabled(StreamCallback callback, int toolCount) {
        Mode mode = getMode();
        return switch (mode) {
            case ENABLED -> true;
            case DISABLED -> false;
            case AUTO -> callback != null && toolCount > 0;
        };
    }

    /**
     * 快速检查（不含条件判断，仅看模式是否可能启用）。
     */
    public static boolean isEnabledOptimistic() {
        return getMode() != Mode.DISABLED;
    }
}
