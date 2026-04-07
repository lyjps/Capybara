package com.co.claudecode.demo;

import com.co.claudecode.demo.agent.AgentEngine;
import com.co.claudecode.demo.agent.AgentRegistry;
import com.co.claudecode.demo.agent.AgentTaskRegistry;
import com.co.claudecode.demo.agent.ConversationMemory;
import com.co.claudecode.demo.agent.SubAgentRunner;
import com.co.claudecode.demo.compact.MicroCompactConfig;
import com.co.claudecode.demo.compact.SessionMemory;
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
import com.co.claudecode.demo.mcp.McpConfigLoader;
import com.co.claudecode.demo.mcp.McpServerConfig;
import com.co.claudecode.demo.mcp.McpServerConnection;
import com.co.claudecode.demo.mcp.client.McpConnectionManager;
import com.co.claudecode.demo.mcp.tool.ListMcpResourcesTool;
import com.co.claudecode.demo.mcp.tool.MappedToolRegistry;
import com.co.claudecode.demo.mcp.tool.McpToolBridge;
import com.co.claudecode.demo.mcp.tool.ReadMcpResourceTool;

import com.co.claudecode.demo.prompt.SystemPromptBuilder;
import com.co.claudecode.demo.skill.SkillDefinition;
import com.co.claudecode.demo.skill.SkillLoader;
import com.co.claudecode.demo.skill.SkillRegistry;
import com.co.claudecode.demo.skill.SkillTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 交互式 REPL 入口。
 * <p>
 * 启动后在终端持续读取用户输入，每条输入触发一轮 agent loop，
 * 模型可以调用工具、多轮推理后返回最终回复，然后等待下一条输入。
 * <p>
 * 启动方式：
 * <pre>
 *   java -jar target/Capybara-1.0-SNAPSHOT.jar [workspace-path]
 * </pre>
 */
public final class InteractiveApplication {

    private static final String BANNER = """
            ╔══════════════════════════════════════════════╗
            ║         Claude Code Java - Interactive       ║
            ╠══════════════════════════════════════════════╣
            ║  Commands:                                   ║
            ║    /quit       - exit                        ║
            ║    /clear      - clear conversation history  ║
            ║    /model      - show current model info     ║
            ║    /agents     - list registered agent types ║
            ║    /tasks      - show running agent tasks    ║
            ║    /compact    - force context compaction    ║
            ║    /context    - show token usage stats      ║
            ║    /mcp        - show MCP server status      ║
            ║    /skills     - list loaded skills          ║
            ╚══════════════════════════════════════════════╝
            """;

    // ---- Token budget 常量 ----
    private static final int CONTEXT_WINDOW_TOKENS = 200_000;
    private static final int MAX_OUTPUT_TOKENS = 16_384;
    private static final int AUTO_COMPACT_BUFFER = 13_000;

    // ---- Agent / 并发常量 ----
    private static final int MAIN_AGENT_MAX_TURNS = 12;
    private static final int SUB_AGENT_MAX_CONCURRENCY = 4;
    private static final int TOOL_ORCHESTRATOR_CONCURRENCY = 4;

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

        // ---- MCP 子系统初始化（amap + xt-search + mt-map 全部通过 MCP 协议直连）----
        List<McpServerConfig> mcpConfigs = McpConfigLoader.loadConfigs(workspaceRoot);
        McpConnectionManager mcpManager = new McpConnectionManager(InteractiveApplication::logEvent);
        if (!mcpConfigs.isEmpty()) {
            System.out.println("MCP     > Loading " + mcpConfigs.size() + " server(s)...");
            mcpManager.connectAll(mcpConfigs);
        }

        // amap 工具：直通桥接（mcp__amap-maps__xxx 命名）
        // 排除 xt-search 和 mt-map，它们使用映射桥接
        Set<String> directBridgeServers = Set.of("amap-maps");
        List<Tool> mcpTools = McpToolBridge.createForServers(mcpManager, directBridgeServers);

        // 如果有资源支持，添加资源工具
        if (mcpManager.hasAnyResources()) {
            mcpTools = new ArrayList<>(mcpTools);
            mcpTools.add(new ListMcpResourcesTool(mcpManager));
            mcpTools.add(new ReadMcpResourceTool(mcpManager));
        }

        // xt-search + mt-map 工具：映射桥接（直接工具名如 meituan_search_mix）
        List<Tool> mappedTools = MappedToolRegistry.createAllTools(mcpManager);

        // ---- Skill 子系统初始化 ----
        List<SkillDefinition> skills = SkillLoader.loadAll(workspaceRoot);
        SkillRegistry skillRegistry = new SkillRegistry(skills);
        if (skillRegistry.hasSkills()) {
            System.out.println("SKILLS  > Loaded " + skillRegistry.size() + " skill(s): "
                    + skillRegistry.allSkills().stream()
                    .map(SkillDefinition::name).toList());
        }

        // 创建 ModelAdapter（需要先注册基础工具到 ToolRegistry）
        List<Tool> allToolsForSchema = new ArrayList<>(baseTools);
        allToolsForSchema.addAll(taskTools);
        allToolsForSchema.addAll(mcpTools);
        allToolsForSchema.addAll(mappedTools);

        // 流式回调
        com.co.claudecode.demo.model.llm.StreamCallback streamCallback = token -> {
            System.out.print(token);
            System.out.flush();
        };

        // SubAgentRunner（子 Agent 不包含 AgentTool 和 SendMessage，防止递归）
        SubAgentRunner subAgentRunner = new SubAgentRunner(
                null, // modelAdapter 稍后设置——先创建所有工具
                permissionPolicy, context, agentTaskRegistry,
                allToolsForSchema, SUB_AGENT_MAX_CONCURRENCY);

        // Agent 工具和通信工具（仅主 Agent 使用）
        AgentTool agentTool = new AgentTool(agentRegistry, subAgentRunner);
        SendMessageTool sendMessageTool = new SendMessageTool(agentTaskRegistry);

        // Skill 工具（有 skill 时才注册）
        SkillTool skillTool = skillRegistry.hasSkills()
                ? new SkillTool(skillRegistry, subAgentRunner, agentRegistry)
                : null;

        // 完整工具列表（主 Agent 使用）
        List<Tool> allTools = new ArrayList<>(allToolsForSchema);
        allTools.add(agentTool);
        allTools.add(sendMessageTool);
        if (skillTool != null) {
            allTools.add(skillTool);
        }

        ToolRegistry toolRegistry = new ToolRegistry(allTools);
        ModelAdapter modelAdapter = ModelAdapterFactory.create(runtimeConfig, toolRegistry, streamCallback);

        // 延迟注入 modelAdapter 到 subAgentRunner（解决循环依赖）
        subAgentRunner.setModelAdapter(modelAdapter);

        // ---- 三级上下文压缩系统初始化 ----
        Set<String> toolNames = allTools.stream()
                .map(t -> t.metadata().name())
                .collect(Collectors.toSet());
        ConversationMemory memory = resetMemory(workspaceRoot, runtimeConfig.modelName(),
                toolNames, mcpManager, skillRegistry);

        System.out.println(BANNER);
        System.out.println("MODEL     > " + runtimeConfig.provider() + " / " + runtimeConfig.modelName());
        System.out.println("WORKSPACE > " + workspaceRoot);
        System.out.println("AGENTS    > " + agentRegistry.allDefinitions().stream()
                .map(d -> d.agentType()).toList());
        System.out.println("TOOLS     > " + toolRegistry.allTools().stream()
                .map(t -> t.metadata().name()).toList());
        if (!mcpConfigs.isEmpty()) {
            long connectedCount = mcpManager.allConnections().stream()
                    .filter(McpServerConnection::isConnected).count();
            System.out.println("MCP       > " + connectedCount + "/" + mcpConfigs.size()
                    + " servers connected, " + (mcpTools.size() + mappedTools.size()) + " tools");
        }
        System.out.println();

        try (ToolOrchestrator toolOrchestrator = new ToolOrchestrator(toolRegistry, permissionPolicy, TOOL_ORCHESTRATOR_CONCURRENCY);
             Scanner scanner = new Scanner(System.in)) {

            AgentEngine engine = new AgentEngine(memory, modelAdapter, toolOrchestrator, context, MAIN_AGENT_MAX_TURNS,
                    null, null, streamCallback, toolRegistry);

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
                    memory = resetMemory(workspaceRoot, runtimeConfig.modelName(),
                            toolNames, mcpManager, skillRegistry);
                    engine = new AgentEngine(memory, modelAdapter, toolOrchestrator, context, MAIN_AGENT_MAX_TURNS,
                            null, null, streamCallback, toolRegistry);
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

                if (input.equalsIgnoreCase("/compact")) {
                    System.out.println("  Forcing context compaction...");
                    var compactResult = engine.getMemory().forceCompact();
                    if (compactResult != null && compactResult.didCompact()) {
                        System.out.println("  " + compactResult.summary());
                        System.out.println("  Saved " + compactResult.tokensSaved() + " tokens");
                    } else {
                        System.out.println("  No compaction needed or possible.");
                    }
                    System.out.println();
                    continue;
                }

                if (input.equalsIgnoreCase("/context")) {
                    var stats = engine.getMemory().getContextStats();
                    System.out.println(stats.format());
                    continue;
                }

                if (input.equalsIgnoreCase("/mcp")) {
                    var mcpConns = mcpManager.allConnections();
                    if (mcpConns.isEmpty()) {
                        System.out.println("  No MCP servers configured.");
                        System.out.println("  Add servers to .mcp.json or ~/.claude/settings.json");
                    } else {
                        System.out.println("  MCP Servers:");
                        for (McpServerConnection conn : mcpConns) {
                            System.out.println("    " + conn.formatStatus());
                        }
                        // 显示映射工具信息
                        if (!mappedTools.isEmpty()) {
                            System.out.println("  Mapped Tools: " + mappedTools.size()
                                    + " (xt-search: " + MappedToolRegistry.createXtSearchTools(mcpManager).size()
                                    + ", mt-map: " + MappedToolRegistry.createMtMapTools(mcpManager).size() + ")");
                        }
                    }
                    System.out.println();
                    continue;
                }

                if (input.equalsIgnoreCase("/skills")) {
                    if (!skillRegistry.hasSkills()) {
                        System.out.println("  No skills loaded.");
                        System.out.println("  Add .md skill files to ~/.claude/skills/ or <project>/.claude/skills/");
                    } else {
                        System.out.println("  Loaded skills (" + skillRegistry.size() + "):");
                        for (SkillDefinition skill : skillRegistry.allSkills()) {
                            System.out.println("    /" + skill.name()
                                    + " [" + skill.source() + "] "
                                    + (skill.context() == SkillDefinition.ExecutionMode.FORK ? "(fork)" : "(inline)")
                                    + (!skill.description().isBlank() ? " — " + skill.description() : ""));
                        }
                    }
                    System.out.println();
                    continue;
                }

                // Skill 斜杠命令：/skill_name args...
                if (input.startsWith("/")) {
                    String cmdBody = input.substring(1);
                    String skillName;
                    String skillArgs;
                    int spaceIdx = cmdBody.indexOf(' ');
                    if (spaceIdx > 0) {
                        skillName = cmdBody.substring(0, spaceIdx).strip();
                        skillArgs = cmdBody.substring(spaceIdx + 1).strip();
                    } else {
                        skillName = cmdBody.strip();
                        skillArgs = "";
                    }

                    SkillDefinition matchedSkill = skillRegistry.findByName(skillName);
                    if (matchedSkill != null) {
                        System.out.println("  executing skill: " + matchedSkill.name() + "\n");
                        String prompt = matchedSkill.resolvePrompt(skillArgs);
                        try {
                            engine.chat(prompt, InteractiveApplication::logEvent);
                            System.out.println();
                            System.out.println();
                        } catch (Exception e) {
                            System.err.println("\u001B[31mERROR > " + e.getMessage() + "\u001B[0m\n");
                        }
                        continue;
                    }

                    // 未知命令
                    System.out.println("  unknown command: " + input);
                    System.out.println("  available: /quit /clear /model /agents /tasks /compact /context /mcp /skills\n");
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
            mcpManager.close();
            subAgentRunner.close();
        }
    }

    private static ConversationMemory resetMemory(Path workspaceRoot,
                                                   String modelName,
                                                   Set<String> toolNames,
                                                   McpConnectionManager mcpManager,
                                                   SkillRegistry skillRegistry) {
        SessionMemory sessionMemory = new SessionMemory();
        MicroCompactConfig microCompactConfig = MicroCompactConfig.ENABLED;

        ConversationMemory memory = new ConversationMemory(
                CONTEXT_WINDOW_TOKENS, MAX_OUTPUT_TOKENS, AUTO_COMPACT_BUFFER,
                sessionMemory, microCompactConfig
        );
        memory.append(ConversationMessage.system(
                SystemPromptBuilder.buildMainPrompt(
                        workspaceRoot, modelName, toolNames, mcpManager,
                        "Chinese", skillRegistry)));
        return memory;
    }

    private static void logEvent(String event) {
        System.out.println("\u001B[90m  " + event + "\u001B[0m");
    }
}
