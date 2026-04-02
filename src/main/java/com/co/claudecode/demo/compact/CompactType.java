package com.co.claudecode.demo.compact;

/**
 * 压缩类型枚举。
 * <p>
 * 对应 TS 原版三级压缩策略 + 无压缩状态。
 */
public enum CompactType {
    /** 无压缩。 */
    NONE,
    /** 微型压缩——清理过期工具结果。 */
    MICRO,
    /** 会话内存压缩——用内存文件替代早期消息。 */
    SESSION_MEMORY,
    /** 完整压缩——结构化摘要生成。 */
    FULL
}
