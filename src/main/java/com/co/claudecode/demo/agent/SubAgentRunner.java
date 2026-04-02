package com.co.claudecode.demo.agent;

import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.model.ModelAdapter;
import com.co.claudecode.demo.tool.PermissionPolicy;
import com.co.claudecode.demo.tool.Tool;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolOrchestrator;
import com.co.claudecode.demo.tool.ToolRegistry;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 子 Agent 执行引擎。
 * <p>
 * 对应 TS 原版 {@code runAgent()} + AgentTool.call() 的核心逻辑。
 * 将"启动一个独立 Agent"封装为同步/异步两种模式：
 * <ul>
 *   <li>同步（runSync）：在当前线程执行，阻塞直到完成</li>
 *   <li>异步（runAsync）：在线程池中执行，立即返回 CompletableFuture</li>
 * </ul>
 * <p>
 * 每个子 Agent 拥有：
 * - 独立的 ConversationMemory（上下文窗口隔离）
 * - 根据 AgentDefinition 过滤后的 ToolRegistry
 * - 独立的 AgentEngine 实例
 */
public final class SubAgentRunner implements AutoCloseable {

    private final ModelAdapter modelAdapter;
    private final PermissionPolicy permissionPolicy;
    private final ToolExecutionContext parentContext;
    private final AgentTaskRegistry taskRegistry;
    private final List<Tool> allTools;
    private final ExecutorService executor;

    public SubAgentRunner(ModelAdapter modelAdapter,
                          PermissionPolicy permissionPolicy,
                          ToolExecutionContext parentContext,
                          AgentTaskRegistry taskRegistry,
                          List<Tool> allTools,
                          int maxConcurrency) {
        this.modelAdapter = modelAdapter;
        this.permissionPolicy = permissionPolicy;
        this.parentContext = parentContext;
        this.taskRegistry = taskRegistry;
        this.allTools = allTools;
        this.executor = Executors.newFixedThreadPool(maxConcurrency);
    }

    /**
     * 同步执行子 Agent。
     * <p>
     * 在当前线程创建独立的 Memory + Engine，运行 Agent 循环直到完成或超时。
     */
    public AgentResult runSync(AgentDefinition agentDef,
                               String prompt,
                               String name,
                               Consumer<String> eventSink) {
        String agentId = AgentTaskRegistry.generateAgentId();
        AgentTask agentTask = new AgentTask(agentId, name, agentDef.agentType(), prompt);
        taskRegistry.register(agentTask);

        long startTime = System.currentTimeMillis();
        try {
            String content = executeAgent(agentDef, prompt, agentId, agentTask, eventSink);
            long duration = System.currentTimeMillis() - startTime;
            AgentResult result = AgentResult.completed(agentId, agentDef.agentType(),
                    content, 0, duration, 0);
            agentTask.markCompleted(result);
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            AgentResult result = AgentResult.failed(agentId, agentDef.agentType(),
                    e.getMessage(), duration);
            agentTask.markFailed(result);
            return result;
        }
    }

    /**
     * 异步执行子 Agent。
     * <p>
     * 在线程池中启动独立 Agent，立即返回 {@link AsyncAgentHandle}（含 agentId）。
     * 调用方可通过 agentId 跟踪进度或向其发送消息。
     * 对应 TS 原版 run_in_background=true 的路径。
     */
    public AsyncAgentHandle runAsync(AgentDefinition agentDef,
                                     String prompt,
                                     String name,
                                     Consumer<String> eventSink) {
        String agentId = AgentTaskRegistry.generateAgentId();
        AgentTask agentTask = new AgentTask(agentId, name, agentDef.agentType(), prompt);
        taskRegistry.register(agentTask);

        CompletableFuture<AgentResult> future = CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            try {
                String content = executeAgent(agentDef, prompt, agentId, agentTask, eventSink);
                long duration = System.currentTimeMillis() - startTime;
                AgentResult result = AgentResult.completed(agentId, agentDef.agentType(),
                        content, 0, duration, 0);
                agentTask.markCompleted(result);
                return result;
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startTime;
                AgentResult result = AgentResult.failed(agentId, agentDef.agentType(),
                        e.getMessage(), duration);
                agentTask.markFailed(result);
                return result;
            }
        }, executor);

        return new AsyncAgentHandle(agentId, agentDef.agentType(), future);
    }

    /**
     * 核心执行逻辑：创建独立环境，运行 Agent 循环。
     */
    private String executeAgent(AgentDefinition agentDef,
                                String prompt,
                                String agentId,
                                AgentTask agentTask,
                                Consumer<String> eventSink) {
        // 1. 根据 AgentDefinition 过滤工具集
        List<Tool> filteredTools = allTools.stream()
                .filter(tool -> agentDef.isToolAllowed(tool.metadata().name()))
                .toList();
        ToolRegistry toolRegistry = new ToolRegistry(filteredTools);

        // 2. 创建独立的 ToolOrchestrator
        try (ToolOrchestrator orchestrator = new ToolOrchestrator(toolRegistry, permissionPolicy, 2)) {
            // 3. 创建独立的 ConversationMemory
            ConversationMemory memory = new ConversationMemory(new SimpleContextCompactor(), 24, 12);

            // 4. 注入系统提示词
            String systemPrompt = agentDef.systemPrompt();
            if (!systemPrompt.isBlank()) {
                memory.append(ConversationMessage.system(systemPrompt
                        + "\n工作区目录: " + parentContext.workspaceRoot()));
            }

            // 5. 创建 AgentEngine 并运行
            AgentEngine engine = new AgentEngine(memory, modelAdapter, orchestrator,
                    parentContext, agentDef.maxTurns(), agentId, agentTask);

            ConversationMessage result = engine.chat(prompt, eventSink);
            return result.plainText();
        }
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
