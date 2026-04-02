package com.co.claudecode.demo;

import com.co.claudecode.demo.agent.AgentEngine;
import com.co.claudecode.demo.agent.ConversationMemory;
import com.co.claudecode.demo.agent.SimpleContextCompactor;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        ConversationMemory memory = new ConversationMemory(new SimpleContextCompactor(), 24, 12);
        memory.append(ConversationMessage.system(
                "优先通过少量高价值工具调用建立事实，再把结论沉淀成产物。"
        ));

        ModelRuntimeConfig runtimeConfig = ModelRuntimeConfig.load();
        TaskStore taskStore = new TaskStore();
        List<Tool> tools = List.of(
                new ListFilesTool(),
                new ReadFileTool(),
                new WriteFileTool(),
                new TaskCreateTool(taskStore),
                new TaskGetTool(taskStore),
                new TaskListTool(taskStore),
                new TaskUpdateTool(taskStore)
        );
        ToolRegistry toolRegistry = new ToolRegistry(tools);
        ModelAdapter modelAdapter = ModelAdapterFactory.create(runtimeConfig, toolRegistry);
        PermissionPolicy permissionPolicy = new WorkspacePermissionPolicy();

        try (ToolOrchestrator toolOrchestrator = new ToolOrchestrator(toolRegistry, permissionPolicy, 4)) {
            System.out.println("MODEL > provider=" + runtimeConfig.provider() + ", model=" + runtimeConfig.modelName());
            AgentEngine engine = new AgentEngine(memory, modelAdapter, toolOrchestrator, context, 12);
            ConversationMessage finalMessage = engine.run(goal, System.out::println);

            System.out.println("\nFINAL > " + finalMessage.plainText());
            System.out.println("FILE  > " + artifactRoot.resolve("architecture-summary.md"));
        }
    }
}
