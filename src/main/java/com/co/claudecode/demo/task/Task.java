package com.co.claudecode.demo.task;

import java.util.List;
import java.util.Map;

/**
 * 任务数据模型。
 * <p>
 * 对应 TS 原版 {@code Task} Zod schema：id, subject, description, status,
 * owner, blocks, blockedBy, metadata。
 * <p>
 * 使用不可变 record。更新时通过 {@link #withStatus(TaskStatus)} 等 wither 方法
 * 创建新实例（与 TS 原版的 Immer-style 不可变更新一致）。
 */
public record Task(
        String id,
        String subject,
        String description,
        TaskStatus status,
        String owner,
        List<String> blocks,
        List<String> blockedBy,
        Map<String, String> metadata
) {

    public Task {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (subject == null || subject.isBlank()) throw new IllegalArgumentException("subject must not be blank");
        description = description == null ? "" : description;
        status = status == null ? TaskStatus.PENDING : status;
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        blockedBy = blockedBy == null ? List.of() : List.copyOf(blockedBy);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public Task withStatus(TaskStatus newStatus) {
        return new Task(id, subject, description, newStatus, owner, blocks, blockedBy, metadata);
    }

    public Task withOwner(String newOwner) {
        return new Task(id, subject, description, status, newOwner, blocks, blockedBy, metadata);
    }

    public Task withSubject(String newSubject) {
        return new Task(id, newSubject, description, status, owner, blocks, blockedBy, metadata);
    }

    public Task withDescription(String newDescription) {
        return new Task(id, subject, newDescription, status, owner, blocks, blockedBy, metadata);
    }

    public Task withBlocks(List<String> newBlocks) {
        return new Task(id, subject, description, status, owner, newBlocks, blockedBy, metadata);
    }

    public Task withBlockedBy(List<String> newBlockedBy) {
        return new Task(id, subject, description, status, owner, blocks, newBlockedBy, metadata);
    }

    /** 格式化为简洁的列表摘要行。 */
    public String toSummaryLine() {
        String ownerStr = (owner != null && !owner.isBlank()) ? " [" + owner + "]" : "";
        String blockedStr = blockedBy.isEmpty() ? "" : " (blocked by: " + String.join(",", blockedBy) + ")";
        return "#" + id + " " + status.name().toLowerCase() + ownerStr + blockedStr + " — " + subject;
    }

    /** 格式化为详细描述。 */
    public String toDetail() {
        StringBuilder sb = new StringBuilder();
        sb.append("Task #").append(id).append('\n');
        sb.append("  subject: ").append(subject).append('\n');
        sb.append("  description: ").append(description).append('\n');
        sb.append("  status: ").append(status.name().toLowerCase()).append('\n');
        if (owner != null && !owner.isBlank()) {
            sb.append("  owner: ").append(owner).append('\n');
        }
        if (!blocks.isEmpty()) {
            sb.append("  blocks: ").append(String.join(", ", blocks)).append('\n');
        }
        if (!blockedBy.isEmpty()) {
            sb.append("  blockedBy: ").append(String.join(", ", blockedBy)).append('\n');
        }
        return sb.toString();
    }
}
