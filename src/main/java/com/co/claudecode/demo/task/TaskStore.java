package com.co.claudecode.demo.task;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存任务存储。
 * <p>
 * 对应 TS 原版 {@code src/utils/tasks.ts} 的文件系统存储，
 * Java 版简化为线程安全的内存实现。使用 ConcurrentHashMap +
 * 原子 ID 生成器，支持多 Agent 线程并发 CRUD。
 */
public final class TaskStore {

    private final ConcurrentHashMap<String, Task> tasks = new ConcurrentHashMap<>();
    private final AtomicInteger idSequence = new AtomicInteger(0);

    /** 创建任务，返回生成的 ID。 */
    public String createTask(String subject, String description) {
        String id = String.valueOf(idSequence.incrementAndGet());
        Task task = new Task(id, subject, description, TaskStatus.PENDING,
                null, List.of(), List.of(), Map.of());
        tasks.put(id, task);
        return id;
    }

    /** 按 ID 获取任务，不存在返回 null。 */
    public Task getTask(String id) {
        return tasks.get(id);
    }

    /** 按 ID 获取任务，不存在抛异常。 */
    public Task requireTask(String id) {
        Task task = tasks.get(id);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: #" + id);
        }
        return task;
    }

    /** 返回所有任务的不可变快照。 */
    public List<Task> listTasks() {
        return List.copyOf(tasks.values());
    }

    /**
     * 更新任务字段。每个非 null 参数将覆盖对应字段。
     *
     * @return 更新后的任务
     */
    public Task updateTask(String id,
                           TaskStatus newStatus,
                           String newSubject,
                           String newDescription,
                           String newOwner,
                           List<String> addBlocks,
                           List<String> addBlockedBy) {
        return tasks.compute(id, (key, existing) -> {
            if (existing == null) {
                throw new IllegalArgumentException("Task not found: #" + id);
            }
            Task updated = existing;
            if (newStatus != null) {
                updated = updated.withStatus(newStatus);
            }
            if (newSubject != null && !newSubject.isBlank()) {
                updated = updated.withSubject(newSubject);
            }
            if (newDescription != null) {
                updated = updated.withDescription(newDescription);
            }
            if (newOwner != null) {
                updated = updated.withOwner(newOwner);
            }
            if (addBlocks != null && !addBlocks.isEmpty()) {
                List<String> merged = new ArrayList<>(updated.blocks());
                for (String b : addBlocks) {
                    if (!merged.contains(b)) merged.add(b);
                }
                updated = updated.withBlocks(merged);
            }
            if (addBlockedBy != null && !addBlockedBy.isEmpty()) {
                List<String> merged = new ArrayList<>(updated.blockedBy());
                for (String b : addBlockedBy) {
                    if (!merged.contains(b)) merged.add(b);
                }
                updated = updated.withBlockedBy(merged);
            }
            return updated;
        });
    }

    /** 删除任务。 */
    public boolean deleteTask(String id) {
        return tasks.remove(id) != null;
    }

    /** 当前任务数量。 */
    public int size() {
        return tasks.size();
    }

    /** 清空所有任务。 */
    public void clear() {
        tasks.clear();
        idSequence.set(0);
    }
}
