package com.co.claudecode.demo.agent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局 Agent 任务注册表。
 * <p>
 * 对应 TS 原版 {@code tasks} 全局状态（localAgentTasks 模块）。
 * 维护所有运行中/已完成的 Agent 任务状态，支持：
 * - 按 ID 查找
 * - 按名称查找（SendMessage 按名寻址）
 * - 消息投递
 * <p>
 * 线程安全：ConcurrentHashMap 存储。
 */
public final class AgentTaskRegistry {

    private final ConcurrentHashMap<String, AgentTask> tasks = new ConcurrentHashMap<>();
    // 名称到 agentId 的映射，支持 SendMessage 的 to: "name" 寻址
    private final ConcurrentHashMap<String, String> nameIndex = new ConcurrentHashMap<>();

    /** 生成唯一 Agent ID（8 字符随机 ID，与 TS 原版一致）。 */
    public static String generateAgentId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** 注册一个 Agent 任务。 */
    public void register(AgentTask task) {
        tasks.put(task.agentId(), task);
        if (task.name() != null && !task.name().isBlank()) {
            nameIndex.put(task.name(), task.agentId());
        }
    }

    /** 按 ID 查找。 */
    public AgentTask findById(String agentId) {
        return tasks.get(agentId);
    }

    /** 按名称查找。 */
    public AgentTask findByName(String name) {
        String agentId = nameIndex.get(name);
        if (agentId == null) return null;
        return tasks.get(agentId);
    }

    /** 按 ID 或名称查找（SendMessage 的 to 字段可以是任意一种）。 */
    public AgentTask findByIdOrName(String target) {
        AgentTask task = findById(target);
        if (task != null) return task;
        return findByName(target);
    }

    /**
     * 向目标 Agent 投递消息。
     *
     * @param target Agent ID 或名称
     * @param message 消息内容
     * @return true 如果投递成功，false 如果目标不存在
     */
    public boolean sendMessage(String target, String message) {
        AgentTask task = findByIdOrName(target);
        if (task == null) return false;
        task.enqueueMessage(message);
        return true;
    }

    /** 获取所有运行中的 Agent 任务。 */
    public List<AgentTask> allRunning() {
        return tasks.values().stream()
                .filter(AgentTask::isRunning)
                .toList();
    }

    /** 获取所有 Agent 任务。 */
    public List<AgentTask> allTasks() {
        return List.copyOf(tasks.values());
    }

    /** 移除已完成/失败的任务（资源回收）。 */
    public int cleanup() {
        List<String> toRemove = tasks.entrySet().stream()
                .filter(e -> !e.getValue().isRunning())
                .map(Map.Entry::getKey)
                .toList();
        for (String id : toRemove) {
            AgentTask removed = tasks.remove(id);
            if (removed != null && removed.name() != null) {
                nameIndex.remove(removed.name());
            }
        }
        return toRemove.size();
    }

    /** 当前注册的任务数量。 */
    public int size() {
        return tasks.size();
    }
}
