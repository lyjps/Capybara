package com.co.claudecode.demo.agent;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 类型注册中心。
 * <p>
 * 对应 TS 原版 {@code agentDefinitions.activeAgents} + {@code getBuiltInAgents()} 逻辑。
 * 启动时注册内置 Agent，运行时可动态注册自定义 Agent（对应 .claude/agents/ 目录加载）。
 * <p>
 * 线程安全：使用 ConcurrentHashMap，支持多 Agent 并发查询。
 */
public final class AgentRegistry {

    private final Map<String, AgentDefinition> agents = new ConcurrentHashMap<>();

    /** 创建空的注册中心。 */
    public AgentRegistry() {
    }

    /** 创建并注册所有内置 Agent 的注册中心。 */
    public static AgentRegistry withBuiltIns() {
        AgentRegistry registry = new AgentRegistry();
        BuiltInAgents.all().forEach(registry::register);
        return registry;
    }

    /** 注册一个 Agent 类型定义。同名覆盖。 */
    public void register(AgentDefinition definition) {
        agents.put(definition.agentType(), definition);
    }

    /**
     * 按类型名查找 Agent 定义。
     *
     * @throws IllegalArgumentException 如果类型不存在
     */
    public AgentDefinition resolve(String agentType) {
        AgentDefinition def = agents.get(agentType);
        if (def == null) {
            throw new IllegalArgumentException("Unknown agent type: " + agentType
                    + ". Available: " + agents.keySet());
        }
        return def;
    }

    /** 查找 Agent 定义，不存在返回 null。 */
    public AgentDefinition findOrNull(String agentType) {
        return agents.get(agentType);
    }

    /** 返回所有已注册的 Agent 定义。 */
    public Collection<AgentDefinition> allDefinitions() {
        return List.copyOf(agents.values());
    }

    /** 已注册的 Agent 类型数量。 */
    public int size() {
        return agents.size();
    }
}
