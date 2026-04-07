package com.co.claudecode.demo;

import com.co.claudecode.demo.agent.AgentEngine;
import com.co.claudecode.demo.agent.ConversationMemory;
import com.co.claudecode.demo.compact.MicroCompactConfig;
import com.co.claudecode.demo.compact.SessionMemory;
import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.model.ModelAdapter;
import com.co.claudecode.demo.model.llm.ModelAdapterFactory;
import com.co.claudecode.demo.model.llm.ModelRuntimeConfig;
import com.co.claudecode.demo.tool.PermissionPolicy;
import com.co.claudecode.demo.tool.Tool;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolOrchestrator;
import com.co.claudecode.demo.tool.ToolRegistry;
import com.co.claudecode.demo.tool.WorkspacePermissionPolicy;
import com.co.claudecode.demo.task.TaskStore;
import com.co.claudecode.demo.tool.impl.ListFilesTool;
import com.co.claudecode.demo.tool.impl.ReadFileTool;
import com.co.claudecode.demo.tool.impl.TaskCreateTool;
import com.co.claudecode.demo.tool.impl.TaskGetTool;
import com.co.claudecode.demo.tool.impl.TaskListTool;
import com.co.claudecode.demo.tool.impl.TaskUpdateTool;
import com.co.claudecode.demo.tool.impl.WriteFileTool;
import com.co.claudecode.demo.mcp.McpConfigLoader;
import com.co.claudecode.demo.mcp.McpServerConfig;
import com.co.claudecode.demo.mcp.client.McpConnectionManager;
import com.co.claudecode.demo.mcp.tool.ListMcpResourcesTool;
import com.co.claudecode.demo.mcp.tool.MappedToolRegistry;
import com.co.claudecode.demo.mcp.tool.McpToolBridge;
import com.co.claudecode.demo.mcp.tool.ReadMcpResourceTool;
import com.co.claudecode.demo.prompt.SystemPromptBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class DemoApplication {

    private DemoApplication() {
    }

    public static void main(String[] args) throws Exception {
        Path workspaceRoot = args.length > 0
                ? Path.of(args[0]).toAbsolutePath().normalize()
                : Path.of(".").toAbsolutePath().normalize();
        String goal = args.length > 1
                ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length))
                : "请分析这个项目的核心设计，并输出一份结构化架构摘要。";

        Path artifactRoot = Path.of("output").toAbsolutePath().normalize();
        Files.createDirectories(artifactRoot);

        ToolExecutionContext context = new ToolExecutionContext(workspaceRoot, artifactRoot);
        ModelRuntimeConfig runtimeConfig = ModelRuntimeConfig.load();

        ConversationMemory memory = new ConversationMemory(
                200_000, 16_384, 13_000,
                new SessionMemory(), MicroCompactConfig.DEFAULT
        );
        memory.append(ConversationMessage.system(
                SystemPromptBuilder.buildDemoPrompt(workspaceRoot, runtimeConfig.modelName())
        ));
        TaskStore taskStore = new TaskStore();

        // MCP 加载（amap + xt-search + mt-map 全部通过 MCP 协议直连）
        List<McpServerConfig> mcpConfigs = McpConfigLoader.loadConfigs(workspaceRoot);
        McpConnectionManager mcpManager = new McpConnectionManager(System.out::println);
        if (!mcpConfigs.isEmpty()) {
            mcpManager.connectAll(mcpConfigs);
        }

        // amap 工具：直通桥接
        Set<String> directBridgeServers = Set.of("amap-maps");
        List<Tool> mcpTools = McpToolBridge.createForServers(mcpManager, directBridgeServers);
        if (mcpManager.hasAnyResources()) {
            mcpTools = new ArrayList<>(mcpTools);
            mcpTools.add(new ListMcpResourcesTool(mcpManager));
            mcpTools.add(new ReadMcpResourceTool(mcpManager));
        }

        // xt-search + mt-map 工具：映射桥接
        List<Tool> mappedTools = MappedToolRegistry.createAllTools(mcpManager);

        List<Tool> tools = new ArrayList<>(List.of(
                new ListFilesTool(),
                new ReadFileTool(),
                new WriteFileTool(),
                new TaskCreateTool(taskStore),
                new TaskGetTool(taskStore),
                new TaskListTool(taskStore),
                new TaskUpdateTool(taskStore)
        ));
        tools.addAll(mcpTools);
        tools.addAll(mappedTools);

        ToolRegistry toolRegistry = new ToolRegistry(tools);
        ModelAdapter modelAdapter = ModelAdapterFactory.create(runtimeConfig, toolRegistry);
        PermissionPolicy permissionPolicy = new WorkspacePermissionPolicy();

        try (ToolOrchestrator toolOrchestrator = new ToolOrchestrator(toolRegistry, permissionPolicy, 4)) {
            System.out.println("MODEL > provider=" + runtimeConfig.provider() + ", model=" + runtimeConfig.modelName());
            AgentEngine engine = new AgentEngine(memory, modelAdapter, toolOrchestrator, context, 12);
            ConversationMessage finalMessage = engine.run(goal, System.out::println);

            System.out.println("\nFINAL > " + finalMessage.plainText());
            System.out.println("FILE  > " + artifactRoot.resolve("architecture-summary.md"));
        } finally {
            mcpManager.close();
        }
    }
}
