package com.co.claudecode.demo.agent;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 运行状态追踪。
 * <p>
 * 对应 TS 原版 {@code LocalAgentTask} 状态结构。
 * 每个运行中或已完成的 Agent 实例对应一个 AgentTask。
 * <p>
 * 核心设计：
 * - {@code pendingMessages} 用于 Agent 间消息传递（SendMessage 投递到此队列）
 * - {@code aborted} 用于外部取消信号（对应 TS 的 AbortController）
 * - {@code result} 在 Agent 完成后填充
 */
public final class AgentTask {

    public enum Status {
        RUNNING, COMPLETED, FAILED, KILLED
    }

    private final String agentId;
    private final String name; // 可选的名称，用于 SendMessage 按名寻址
    private final String agentType;
    private final String prompt;
    private final long startTime;

    private volatile Status status = Status.RUNNING;
    private volatile long endTime;
    private volatile AgentResult result;

    private final ConcurrentLinkedQueue<String> pendingMessages = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean aborted = new AtomicBoolean(false);

    public AgentTask(String agentId, String name, String agentType, String prompt) {
        this.agentId = agentId;
        this.name = name;
        this.agentType = agentType;
        this.prompt = prompt;
        this.startTime = System.currentTimeMillis();
    }

    // ---- 状态管理 ----

    public void markCompleted(AgentResult result) {
        this.result = result;
        this.status = Status.COMPLETED;
        this.endTime = System.currentTimeMillis();
    }

    public void markFailed(AgentResult result) {
        this.result = result;
        this.status = Status.FAILED;
        this.endTime = System.currentTimeMillis();
    }

    public void markKilled() {
        this.aborted.set(true);
        this.status = Status.KILLED;
        this.endTime = System.currentTimeMillis();
    }

    // ---- 消息队列 ----

    /** 投递消息到此 Agent（其他 Agent 调用）。 */
    public void enqueueMessage(String message) {
        pendingMessages.add(message);
    }

    /** 消费所有待处理消息（Agent 自身调用）。 */
    public java.util.List<String> drainMessages() {
        java.util.List<String> messages = new java.util.ArrayList<>();
        String msg;
        while ((msg = pendingMessages.poll()) != null) {
            messages.add(msg);
        }
        return messages;
    }

    public boolean hasPendingMessages() {
        return !pendingMessages.isEmpty();
    }

    // ---- 取消信号 ----

    public boolean isAborted() {
        return aborted.get();
    }

    public void abort() {
        aborted.set(true);
    }

    // ---- Getters ----

    public String agentId() { return agentId; }
    public String name() { return name; }
    public String agentType() { return agentType; }
    public String prompt() { return prompt; }
    public Status status() { return status; }
    public long startTime() { return startTime; }
    public long endTime() { return endTime; }
    public AgentResult result() { return result; }

    public boolean isRunning() { return status == Status.RUNNING; }
}
