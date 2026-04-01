package com.co.claudecode.demo.model;

import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.message.ToolCallBlock;
import com.co.claudecode.demo.message.ToolResultBlock;
import com.co.claudecode.demo.tool.ToolExecutionContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 这里用规则模型，不是为了模拟真实 LLM 的智力，而是为了把主循环
 * 的工程边界单独拿出来演示。只要 reply 仍然通过 message + tool_call
 * 产生影响，agent loop 的核心结构就已经成立。
 */
public final class RuleBasedModelAdapter implements ModelAdapter {

    private final AtomicInteger toolSequence = new AtomicInteger(1);

    @Override
    public ConversationMessage nextReply(List<ConversationMessage> conversation, ToolExecutionContext context) {
        if (!hasSuccessfulResult(conversation, "list_files")) {
            return ConversationMessage.assistant(
                    "先建立工作区轮廓，再决定读哪些文件。这样做能先确认边界，再下结论。",
                    List.of(toolCall("list_files", Map.of("path", ".", "depth", "3")))
            );
        }

        if (!hasSuccessfulResult(conversation, "read_file")) {
            List<String> candidates = pickImportantFiles(latestSuccessfulContent(conversation, "list_files"));
            List<ToolCallBlock> calls = candidates.stream()
                    .map(path -> toolCall("read_file", Map.of("path", path)))
                    .toList();
            return ConversationMessage.assistant(
                    "目录结构已经足够，接下来只读最能代表架构边界的少量文件，避免把上下文预算浪费在低价值细节上。",
                    calls
            );
        }

        if (!hasSuccessfulResult(conversation, "write_file")) {
            String summary = buildArchitectureMemo(conversation, context.workspaceRoot());
            return ConversationMessage.assistant(
                    "关键信息已经够用了，先把结论写成产物，避免分析只停留在当前对话里。",
                    List.of(toolCall("write_file", Map.of(
                            "path", "architecture-summary.md",
                            "content", summary
                    )))
            );
        }

        return ConversationMessage.assistant(
                "分析完成。这个 demo 保留了原项目最关键的四件事：消息驱动循环、工具元数据、权限治理和上下文压缩。产物已经写到 output/architecture-summary.md。",
                List.of()
        );
    }

    private ToolCallBlock toolCall(String toolName, Map<String, String> input) {
        return new ToolCallBlock("tool-" + toolSequence.getAndIncrement(), toolName, input);
    }

    private boolean hasSuccessfulResult(List<ConversationMessage> conversation, String toolName) {
        return conversation.stream()
                .flatMap(message -> message.toolResults().stream())
                .anyMatch(result -> result.toolName().equals(toolName) && !result.error());
    }

    private String latestSuccessfulContent(List<ConversationMessage> conversation, String toolName) {
        List<ToolResultBlock> results = conversation.stream()
                .flatMap(message -> message.toolResults().stream())
                .filter(result -> result.toolName().equals(toolName) && !result.error())
                .toList();
        if (results.isEmpty()) {
            return "";
        }
        return results.get(results.size() - 1).content();
    }

    private List<String> pickImportantFiles(String listFilesResult) {
        List<String> files = listFilesResult.lines()
                .filter(line -> line.startsWith("FILE "))
                .map(line -> line.substring("FILE ".length()).trim())
                .filter(this::isReadableTextFile)
                .filter(path -> !path.startsWith("docs/"))
                .filter(path -> !path.contains("node_modules"))
                .toList();

        if (files.isEmpty()) {
            return List.of("README.md");
        }

        Set<String> available = new LinkedHashSet<>(files);
        List<String> preferred = List.of(
                "README.md",
                "README_CN.md",
                "src/query.ts",
                "src/QueryEngine.ts",
                "src/Tool.ts",
                "src/services/tools/toolOrchestration.ts",
                "src/Task.ts"
        );

        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (String candidate : preferred) {
            if (available.contains(candidate)) {
                selected.add(candidate);
            }
            if (selected.size() >= 5) {
                return List.copyOf(selected);
            }
        }

        files.stream()
                .sorted(Comparator
                        .comparingInt(this::scoreFile)
                        .reversed()
                        .thenComparing(path -> path))
                .limit(5)
                .forEach(selected::add);

        return List.copyOf(selected);
    }

    private boolean isReadableTextFile(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md")
                || lower.endsWith(".ts")
                || lower.endsWith(".tsx")
                || lower.endsWith(".java")
                || lower.endsWith(".kt")
                || lower.endsWith(".py");
    }

    private int scoreFile(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        int score = 0;
        if (lower.contains("readme")) score += 100;
        if (lower.contains("queryengine")) score += 95;
        if (lower.endsWith("/query.ts") || lower.equals("src/query.ts")) score += 90;
        if (lower.endsWith("/tool.ts") || lower.equals("src/tool.ts")) score += 85;
        if (lower.contains("toolorchestration")) score += 75;
        if (lower.contains("toolexecution")) score += 70;
        if (lower.contains("compact")) score += 65;
        if (lower.contains("main")) score += 60;
        if (lower.startsWith("src/")) score += 60;
        return score;
    }

    private String buildArchitectureMemo(List<ConversationMessage> conversation, Path workspaceRoot) {
        List<ToolResultBlock> readResults = conversation.stream()
                .flatMap(message -> message.toolResults().stream())
                .filter(result -> result.toolName().equals("read_file") && !result.error())
                .toList();

        List<String> studiedFiles = readResults.stream()
                .map(this::extractPath)
                .toList();

        String corpus = readResults.stream()
                .map(ToolResultBlock::content)
                .reduce("", (left, right) -> left + "\n" + right)
                .toLowerCase(Locale.ROOT);

        List<String> findings = new ArrayList<>();
        if (containsAny(corpus, "queryengine", "submitmessage", "asyncgenerator")) {
            findings.add("入口层和执行层被明确拆开，说明系统要同时支撑交互式场景与 headless/SDK 场景，而不是只服务一个 CLI。");
        }
        if (containsAny(corpus, "tool_use", "tool_result", "runtools", "streamingtoolexecutor")) {
            findings.add("主循环围绕消息推进，而不是围绕函数调用推进。这样工具反馈天然能回流到下一轮推理上下文。");
        }
        if (containsAny(corpus, "checkpermissions", "isconcurrencysafe", "readonly", "validateinput")) {
            findings.add("工具协议显式暴露并发、安全和校验元数据，说明作者把“能不能做”与“怎么做”分成了不同层。");
        }
        if (containsAny(corpus, "compact", "microcompact", "snip", "context collapse")) {
            findings.add("上下文压缩是内建机制，不是补丁。原因是 agent 一旦多轮调用工具，历史增长会非常快。");
        }
        if (containsAny(corpus, "mcp", "plugin", "server")) {
            findings.add("扩展能力通过统一工具面并入主循环，而不是为每类外部能力单独开分支流程。");
        }
        if (findings.isEmpty()) {
            findings.add("这个工作区的结构仍然体现了 agent loop 的基本套路：先建上下文，再执行工具，再把结果回填到历史。");
        }

        List<String> demoMapping = List.of(
                "`AgentEngine` 对应原项目的 query loop，用多轮消息驱动系统前进。",
                "`ConversationMemory` 对应上下文窗口治理，负责 compact 而不是让模型无限背负旧历史。",
                "`ToolOrchestrator` 对应工具编排层，只让声明为安全的调用并发。",
                "`WorkspacePermissionPolicy` 对应权限层，说明安全策略不应该散落在每个工具实现里。"
        );

        return """
                # 架构摘要

                分析工作区: %s
                参考文件:
                %s

                ## 核心判断
                %s

                ## 为什么 Java demo 这样分层
                %s
                """.formatted(
                workspaceRoot,
                studiedFiles.stream()
                        .map(file -> "- " + file)
                        .reduce("", (left, right) -> left + (left.isBlank() ? "" : "\n") + right),
                findings.stream()
                        .map(line -> "- " + line)
                        .reduce("", (left, right) -> left + (left.isBlank() ? "" : "\n") + right),
                demoMapping.stream()
                        .map(line -> "- " + line)
                        .reduce("", (left, right) -> left + (left.isBlank() ? "" : "\n") + right)
        );
    }

    private boolean containsAny(String corpus, String... keywords) {
        for (String keyword : keywords) {
            if (corpus.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String extractPath(ToolResultBlock result) {
        String firstLine = result.content().lines().findFirst().orElse("PATH: unknown");
        if (firstLine.startsWith("PATH: ")) {
            return firstLine.substring("PATH: ".length()).trim();
        }
        return "unknown";
    }
}
