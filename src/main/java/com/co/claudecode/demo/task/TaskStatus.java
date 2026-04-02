package com.co.claudecode.demo.task;

/**
 * 任务状态枚举。
 * <p>
 * 对应 TS 原版 {@code TASK_STATUSES = ['pending', 'in_progress', 'completed']}。
 * 状态流转：PENDING → IN_PROGRESS → COMPLETED。
 */
public enum TaskStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED;

    /** 从字符串解析，忽略大小写和下划线/连字符差异。 */
    public static TaskStatus fromString(String s) {
        if (s == null) {
            throw new IllegalArgumentException("Status must not be null");
        }
        return switch (s.toLowerCase().replace('-', '_')) {
            case "pending" -> PENDING;
            case "in_progress" -> IN_PROGRESS;
            case "completed" -> COMPLETED;
            default -> throw new IllegalArgumentException("Unknown task status: " + s);
        };
    }
}
