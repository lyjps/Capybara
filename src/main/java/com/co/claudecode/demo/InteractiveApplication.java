package com.co.claudecode.demo;

import com.co.claudecode.demo.agent.AgentEngine;
import com.co.claudecode.demo.agent.AgentRegistry;
import com.co.claudecode.demo.agent.AgentTaskRegistry;
import com.co.claudecode.demo.agent.ConversationMemory;
import com.co.claudecode.demo.agent.SimpleContextCompactor;
import com.co.claudecode.demo.agent.SubAgentRunner;
import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.model.ModelAdapter;
import com.co.claudecode.demo.model.llm.ModelAdapterFactory;
import com.co.claudecode.demo.model.llm.ModelRuntimeConfig;
import com.co.claudecode.demo.task.TaskStore;
import com.co.claudecode.demo.tool.PermissionPolicy;
import com.co.claudecode.demo.tool.Tool;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolOrchestrator;
import com.co.claudecode.demo.tool.ToolRegistry;
import com.co.claudecode.demo.tool.WorkspacePermissionPolicy;
import com.co.claudecode.demo.tool.impl.AgentTool;
import com.co.claudecode.demo.tool.impl.ListFilesTool;
import com.co.claudecode.demo.tool.impl.ReadFileTool;
import com.co.claudecode.demo.tool.impl.SendMessageTool;
import com.co.claudecode.demo.tool.impl.TaskCreateTool;
import com.co.claudecode.demo.tool.impl.TaskGetTool;
import com.co.claudecode.demo.tool.impl.TaskListTool;
import com.co.claudecode.demo.tool.impl.TaskUpdateTool;
import com.co.claudecode.demo.tool.impl.WriteFileTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * 交互式 REPL 入口。
 * <p>
 * 启动后在终端持续读取用户输入，每条输入触发一轮 agent loop，
 * 模型可以调用工具、多轮推理后返回最终回复，然后等待下一条输入。
 * <p>
 * 启动方式：
 * <pre>
 *   java -jar target/claude-code-java-demo-1.0-SNAPSHOT.jar [workspace-path]
 * </pre>
 */
public final class InteractiveApplication {

    private static final String BANNER = """
            ╔══════════════════════════════════════════════╗
            ║         Claude Code Java - Interactive       ║
            ╠══════════════════════════════════════════════╣
            ║  Commands:                                   ║
            ║    /quit   - exit                            ║
            ║    /clear  - clear conversation history      ║
            ║    /model  - show current model info         ║
            ║    /agents - list registered agent types     ║
            ║    /tasks  - show running agent tasks        ║
            ╚══════════════════════════════════════════════╝
            """;

    private InteractiveApplication() {
    }

    public static void main(String[] args) throws Exception {
        Path workspaceRoot = args.length > 0
                ? Path.of(args[0]).toAbsolutePath().normalize()
                : Path.of(".").toAbsolutePath().normalize();

        Path artifactRoot = Path.of("output").toAbsolutePath().normalize();
        Files.createDirectories(artifactRoot);

        ToolExecutionContext context = new ToolExecutionContext(workspaceRoot, artifactRoot);
        ModelRuntimeConfig runtimeConfig = ModelRuntimeConfig.load();

        // ---- Agent 子系统初始化 ----
        AgentRegistry agentRegistry = AgentRegistry.withBuiltIns();
        AgentTaskRegistry agentTaskRegistry = new AgentTaskRegistry();
        TaskStore taskStore = new TaskStore();
        PermissionPolicy permissionPolicy = new WorkspacePermissionPolicy();

        // 基础工具（子 Agent 也可使用这些）
        List<Tool> baseTools = List.of(
                new ListFilesTool(),
                new ReadFileTool(),
                new WriteFileTool()
        );

        // Task 工具
        List<Tool> taskTools = List.of(
                new TaskCreateTool(taskStore),
                new TaskGetTool(taskStore),
                new TaskListTool(taskStore),
                new TaskUpdateTool(taskStore)
        );

        // 创建 ModelAdapter（需要先注册基础工具到 ToolRegistry）
        List<Tool> allToolsForSchema = new ArrayList<>(baseTools);
        allToolsForSchema.addAll(taskTools);

        // 流式回调
        com.co.claudecode.demo.model.llm.StreamCallback streamCallback = token -> {
            System.out.print(token);
            System.out.flush();
        };

        // SubAgentRunner（子 Agent 不包含 AgentTool 和 SendMessage，防止递归）
        SubAgentRunner subAgentRunner = new SubAgentRunner(
                null, // modelAdapter 稍后设置——先创建所有工具
                permissionPolicy, context, agentTaskRegistry,
                allToolsForSchema, 4);

        // Agent 工具和通信工具（仅主 Agent 使用）
        AgentTool agentTool = new AgentTool(agentRegistry, subAgentRunner);
        SendMessageTool sendMessageTool = new SendMessageTool(agentTaskRegistry);

        // 完整工具列表（主 Agent 使用）
        List<Tool> allTools = new ArrayList<>(allToolsForSchema);
        allTools.add(agentTool);
        allTools.add(sendMessageTool);

        ToolRegistry toolRegistry = new ToolRegistry(allTools);
        ModelAdapter modelAdapter = ModelAdapterFactory.create(runtimeConfig, toolRegistry, streamCallback);

        // 用反射替换 subAgentRunner 中的 modelAdapter（解决循环依赖）
        // 注意：这是一种权宜之计，产品级代码应该用 Provider/Lazy 模式
        var maField = SubAgentRunner.class.getDeclaredField("modelAdapter");
        maField.setAccessible(true);
        maField.set(subAgentRunner, modelAdapter);

        ConversationMemory memory = new ConversationMemory(new SimpleContextCompactor(), 24, 12);
        memory.append(ConversationMessage.system(
                "你是一个代码分析助手。工作区目录是: " + workspaceRoot
                        + "\n你可以使用 list_files、read_file、write_file 工具来探索和操作文件。"
                        + "\n你可以使用 agent 工具启动子 Agent 执行复杂的多步骤任务。"
                        + "\n你可以使用 task_create、task_get、task_list、task_update 工具管理任务列表。"
                        + "\n你可以使用 send_message 工具向其他 Agent 发送消息。"
                        + "\n请用中文回答用户的问题。回答要简洁、有结构。"
        ));

        System.out.println(BANNER);
        System.out.println("MODEL     > " + runtimeConfig.provider() + " / " + runtimeConfig.modelName());
        System.out.println("WORKSPACE > " + workspaceRoot);
        System.out.println("AGENTS    > " + agentRegistry.allDefinitions().stream()
                .map(d -> d.agentType()).toList());
        System.out.println("TOOLS     > " + toolRegistry.allTools().stream()
                .map(t -> t.metadata().name()).toList());
        System.out.println();

        try (ToolOrchestrator toolOrchestrator = new ToolOrchestrator(toolRegistry, permissionPolicy, 4);
             Scanner scanner = new Scanner(System.in)) {

            AgentEngine engine = new AgentEngine(memory, modelAdapter, toolOrchestrator, context, 12);

            while (true) {
                System.out.print("\u001B[36m> \u001B[0m");
                System.out.flush();

                if (!scanner.hasNextLine()) {
                    break;
                }

                String input = scanner.nextLine().trim();

                if (input.isEmpty()) {
                    continue;
                }

                // 特殊命令
                if (input.equalsIgnoreCase("/quit") || input.equalsIgnoreCase("/exit")) {
                    System.out.println("Bye!");
                    break;
                }

                if (input.equalsIgnoreCase("/clear")) {
                    memory = resetMemory(workspaceRoot);
                    engine = new AgentEngine(memory, modelAdapter, toolOrchestrator, context, 12);
                    System.out.println("  conversation cleared.\n");
                    continue;
                }

                if (input.equalsIgnoreCase("/model")) {
                    System.out.println("  provider  = " + runtimeConfig.provider());
                    System.out.println("  model     = " + runtimeConfig.modelName());
                    System.out.println("  base-url  = " + runtimeConfig.baseUrl());
                    System.out.println("  max-tokens= " + runtimeConfig.maxOutputTokens());
                    System.out.println();
                    continue;
                }

                if (input.equalsIgnoreCase("/agents")) {
                    System.out.println("  Registered agent types:");
                    agentRegistry.allDefinitions().forEach(d ->
                            System.out.println("    " + d.agentType() + " [" + d.source() + "]"
                                    + (d.readOnly() ? " (read-only)" : "")));
                    System.out.println();
                    continue;
                }

                if (input.equalsIgnoreCase("/tasks")) {
                    var tasks = agentTaskRegistry.allTasks();
                    if (tasks.isEmpty()) {
                        System.out.println("  No agent tasks.\n");
                    } else {
                        System.out.println("  Agent tasks:");
                        tasks.forEach(t -> System.out.println("    " + t.agentId()
                                + " [" + t.agentType() + "] " + t.status()
                                + (t.name() != null ? " name=" + t.name() : "")));
                        System.out.println();
                    }
                    continue;
                }

                if (input.startsWith("/")) {
                    System.out.println("  unknown command: " + input);
                    System.out.println("  available: /quit /clear /model /agents /tasks\n");
                    continue;
                }

                // 正常对话
                try {
                    engine.chat(input, InteractiveApplication::logEvent);
                    System.out.println();
                    System.out.println();
                } catch (Exception e) {
                    System.err.println("\u001B[31mERROR > " + e.getMessage() + "\u001B[0m\n");
                }
            }
        } finally {
            subAgentRunner.close();
        }
    }

    private static ConversationMemory resetMemory(Path workspaceRoot) {
        ConversationMemory memory = new ConversationMemory(new SimpleContextCompactor(), 24, 12);
        memory.append(ConversationMessage.system(
                "你是一个代码分析助手。工作区目录是: " + workspaceRoot
                        + "\n你可以使用 list_files、read_file、write_file 工具来探索和操作文件。"
                        + "\n你可以使用 agent 工具启动子 Agent 执行复杂的多步骤任务。"
                        + "\n你可以使用 task_create、task_get、task_list、task_update 工具管理任务列表。"
                        + "\n你可以使用 send_message 工具向其他 Agent 发送消息。"
                        + "\n请用中文回答用户的问题。回答要简洁、有结构。"
        ));
        return memory;
    }

    private static void logEvent(String event) {
        System.out.println("\u001B[90m  " + event + "\u001B[0m");
    }
}
