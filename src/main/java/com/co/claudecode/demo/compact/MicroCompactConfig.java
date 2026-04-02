package com.co.claudecode.demo.compact;

/**
 * 微型压缩配置。
 * <p>
 * 对应 TS 原版 {@code timeBasedMCConfig.ts} 中的 Yd_ 配置对象。
 *
 * @param enabled             是否启用微压缩（默认 false）
 * @param gapThresholdMinutes 对话间隔超过此分钟数才触发（默认 60）
 * @param keepRecent          保留最近多少个工具结果不清理（默认 5）
 */
public record MicroCompactConfig(
        boolean enabled,
        long gapThresholdMinutes,
        int keepRecent
) {
    /** 默认配置：关闭状态。 */
    public static final MicroCompactConfig DEFAULT = new MicroCompactConfig(false, 60, 5);

    /** 启用状态的默认配置。 */
    public static final MicroCompactConfig ENABLED = new MicroCompactConfig(true, 60, 5);

    public MicroCompactConfig {
        if (gapThresholdMinutes <= 0) gapThresholdMinutes = 60;
        if (keepRecent < 0) keepRecent = 5;
    }
}
