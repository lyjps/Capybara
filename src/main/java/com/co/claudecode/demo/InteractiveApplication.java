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
import com.co.claudecode.demo.tool.impl.ListFilesTool;
import com.co.claudecode.demo.tool.impl.ReadFileTool;
import com.co.claudecode.demo.tool.impl.WriteFileTool;

import java.nio.file.Files;
import java.nio.file.Path;
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
 *   mvn compile exec:java -Dexec.mainClass=com.co.claudecode.demo.InteractiveApplication
 *
 *   # 或指定工作区目录：
 *   mvn compile exec:java -Dexec.mainClass=com.co.claudecode.demo.InteractiveApplication \
 *       -Dexec.args="/path/to/your/project"
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

        List<Tool> tools = List.of(
                new ListFilesTool(),
                new ReadFileTool(),
                new WriteFileTool()
        );
        ToolRegistry toolRegistry = new ToolRegistry(tools);
        // 流式回调：收到 token 立即打印到终端（打字机效果）
        com.co.claudecode.demo.model.llm.StreamCallback streamCallback = token -> {
            System.out.print(token);
            System.out.flush();
        };
        ModelAdapter modelAdapter = ModelAdapterFactory.create(runtimeConfig, toolRegistry, streamCallback);
        PermissionPolicy permissionPolicy = new WorkspacePermissionPolicy();

        ConversationMemory memory = new ConversationMemory(new SimpleContextCompactor(), 24, 12);
        memory.append(ConversationMessage.system(
                "你是一个代码分析助手。工作区目录是: " + workspaceRoot
                        + "\n你可以使用 list_files、read_file、write_file 工具来探索和操作文件。"
                        + "\n请用中文回答用户的问题。回答要简洁、有结构。"
        ));

        System.out.println(BANNER);
        System.out.println("MODEL     > " + runtimeConfig.provider() + " / " + runtimeConfig.modelName());
        System.out.println("WORKSPACE > " + workspaceRoot);
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

                if (input.startsWith("/")) {
                    System.out.println("  unknown command: " + input);
                    System.out.println("  available: /quit /clear /model\n");
                    continue;
                }

                // 正常对话
                try {
                    // 文本通过 streamCallback 实时打印，这里只需收尾换行
                    engine.chat(input, InteractiveApplication::logEvent);
                    System.out.println();
                    System.out.println();
                } catch (Exception e) {
                    System.err.println("\u001B[31mERROR > " + e.getMessage() + "\u001B[0m\n");
                }
            }
        }
    }

    private static ConversationMemory resetMemory(Path workspaceRoot) {
        ConversationMemory memory = new ConversationMemory(new SimpleContextCompactor(), 24, 12);
        memory.append(ConversationMessage.system(
                "你是一个代码分析助手。工作区目录是: " + workspaceRoot
                        + "\n你可以使用 list_files、read_file、write_file 工具来探索和操作文件。"
                        + "\n请用中文回答用户的问题。回答要简洁、有结构。"
        ));
        return memory;
    }

    private static void logEvent(String event) {
        // 用灰色输出调试信息，和最终回复区分
        System.out.println("\u001B[90m  " + event + "\u001B[0m");
    }
}
