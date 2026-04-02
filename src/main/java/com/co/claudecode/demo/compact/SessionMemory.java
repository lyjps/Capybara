package com.co.claudecode.demo.compact;

import com.co.claudecode.demo.message.ContentBlock;
import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.message.MessageRole;
import com.co.claudecode.demo.message.TextBlock;
import com.co.claudecode.demo.message.ToolCallBlock;
import com.co.claudecode.demo.message.ToolResultBlock;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 会话内存核心服务。
 * <p>
 * 对应 TS 原版 {@code src/services/SessionMemory/sessionMemory.ts}。
 * 将对话中的关键信息提取到一个结构化的 Markdown 模板中，
 * 用于在压缩时替代被丢弃的早期消息。
 * <p>
 * 10 节模板结构与 TS 的 MDK 变量完全对齐。
 * <p>
 * Java 版使用规则提取（不调用 LLM），从对话消息中自动填充各节。
 */
public final class SessionMemory {

    /** 每节最大 Token 数（对应 TS up8 = 2000）。 */
    public static final int MAX_SECTION_TOKENS = 2000;

    /** 整个会话内存文件最大 Token 数（对应 TS JDK = 12000）。 */
    public static final int MAX_TOTAL_TOKENS = 12000;

    private static final String TEMPLATE = """
            # Session Title
            _A short descriptive title for the session_

            # Current State
            _What is actively being worked on right now?_

            # Task Specification
            _What did the user ask to build?_

            # Files and Functions
            _What are the important files?_

            # Workflow
            _What tools are usually called and in what order?_

            # Errors & Corrections
            _Errors encountered and how they were fixed_

            # Codebase and System Documentation
            _Important system components and how they fit together_

            # Learnings
            _What has worked well? What to avoid?_

            # Key Results
            _Output results, answers, tables, or documents_

            # Worklog
            _Step by step, what was done?_
            """;

    private String content;

    public SessionMemory() {
        this.content = TEMPLATE;
    }

    /** 返回空模板。 */
    public static String getTemplate() {
        return TEMPLATE;
    }

    /** 返回当前内存内容。 */
    public String getCurrentContent() {
        return content;
    }

    /** 更新内存内容。 */
    public void updateContent(String newContent) {
        this.content = newContent;
    }

    /** 内容是否为空/仅模板。 */
    public boolean isEmpty() {
        return content == null || content.isBlank() || content.trim().equals(TEMPLATE.trim());
    }

    /**
     * 从对话消息中提取关键信息，填充到模板各节。
     * <p>
     * 使用规则提取（不调用 LLM API），扫描消息中的各类内容。
     */
    public String extractMemoryFromConversation(List<ConversationMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return TEMPLATE;
        }

        String sessionTitle = extractSessionTitle(messages);
        String currentState = extractCurrentState(messages);
        String taskSpec = extractTaskSpecification(messages);
        String filesAndFunctions = extractFilesAndFunctions(messages);
        String workflow = extractWorkflow(messages);
        String errors = extractErrors(messages);
        String keyResults = extractKeyResults(messages);
        String worklog = extractWorklog(messages);

        StringBuilder sb = new StringBuilder();
        sb.append("# Session Title\n");
        sb.append(truncateSection(sessionTitle)).append("\n\n");
        sb.append("# Current State\n");
        sb.append(truncateSection(currentState)).append("\n\n");
        sb.append("# Task Specification\n");
        sb.append(truncateSection(taskSpec)).append("\n\n");
        sb.append("# Files and Functions\n");
        sb.append(truncateSection(filesAndFunctions)).append("\n\n");
        sb.append("# Workflow\n");
        sb.append(truncateSection(workflow)).append("\n\n");
        sb.append("# Errors & Corrections\n");
        sb.append(truncateSection(errors)).append("\n\n");
        sb.append("# Codebase and System Documentation\n");
        sb.append("_No documentation extracted_\n\n");
        sb.append("# Learnings\n");
        sb.append("_No learnings extracted_\n\n");
        sb.append("# Key Results\n");
        sb.append(truncateSection(keyResults)).append("\n\n");
        sb.append("# Worklog\n");
        sb.append(truncateSection(worklog)).append("\n");

        String result = sb.toString();
        this.content = result;
        return result;
    }

    private String extractSessionTitle(List<ConversationMessage> messages) {
        for (ConversationMessage msg : messages) {
            if (msg.role() == MessageRole.USER) {
                String text = msg.plainText();
                if (!text.isBlank()) {
                    return text.length() > 60 ? text.substring(0, 60) + "..." : text;
                }
            }
        }
        return "Untitled session";
    }

    private String extractCurrentState(List<ConversationMessage> messages) {
        List<String> recentAssistant = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0 && recentAssistant.size() < 3; i--) {
            ConversationMessage msg = messages.get(i);
            if (msg.role() == MessageRole.ASSISTANT) {
                String text = msg.plainText();
                if (!text.isBlank()) {
                    recentAssistant.add(0, text.length() > 200 ? text.substring(0, 200) + "..." : text);
                }
            }
        }
        if (recentAssistant.isEmpty()) {
            return "No recent assistant state";
        }
        return String.join("\n\n", recentAssistant);
    }

    private String extractTaskSpecification(List<ConversationMessage> messages) {
        for (ConversationMessage msg : messages) {
            if (msg.role() == MessageRole.USER) {
                String text = msg.plainText();
                if (!text.isBlank()) {
                    return text.length() > 500 ? text.substring(0, 500) + "..." : text;
                }
            }
        }
        return "No task specification found";
    }

    private String extractFilesAndFunctions(List<ConversationMessage> messages) {
        Set<String> files = new LinkedHashSet<>();
        for (ConversationMessage msg : messages) {
            for (ContentBlock block : msg.blocks()) {
                if (block instanceof ToolCallBlock tcb) {
                    if ("read_file".equals(tcb.toolName()) || "write_file".equals(tcb.toolName())) {
                        String path = tcb.input().get("path");
                        if (path != null && !path.isBlank()) {
                            files.add(path);
                        }
                    }
                }
            }
        }
        if (files.isEmpty()) {
            return "No files accessed";
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String file : files) {
            sb.append("- ").append(file).append("\n");
            count++;
            if (count >= 20) {
                sb.append("... and more files\n");
                break;
            }
        }
        return sb.toString();
    }

    private String extractWorkflow(List<ConversationMessage> messages) {
        List<String> toolSequence = new ArrayList<>();
        for (ConversationMessage msg : messages) {
            for (ContentBlock block : msg.blocks()) {
                if (block instanceof ToolCallBlock tcb) {
                    String params = tcb.input().toString();
                    String shortened = params.length() > 50 ? params.substring(0, 50) + "..." : params;
                    toolSequence.add(tcb.toolName() + "(" + shortened + ")");
                }
            }
        }
        if (toolSequence.isEmpty()) {
            return "No tool calls recorded";
        }
        // Show last 15 tool calls
        int start = Math.max(0, toolSequence.size() - 15);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < toolSequence.size(); i++) {
            sb.append((i + 1)).append(". ").append(toolSequence.get(i)).append("\n");
        }
        return sb.toString();
    }

    private String extractErrors(List<ConversationMessage> messages) {
        List<String> errors = new ArrayList<>();
        for (ConversationMessage msg : messages) {
            for (ContentBlock block : msg.blocks()) {
                if (block instanceof ToolResultBlock trb && trb.error()) {
                    String content = trb.content();
                    String shortened = content.length() > 200 ? content.substring(0, 200) + "..." : content;
                    errors.add("- [" + trb.toolName() + "] " + shortened);
                }
            }
        }
        if (errors.isEmpty()) {
            return "No errors encountered";
        }
        return String.join("\n", errors);
    }

    private String extractKeyResults(List<ConversationMessage> messages) {
        List<String> results = new ArrayList<>();
        for (ConversationMessage msg : messages) {
            for (ContentBlock block : msg.blocks()) {
                if (block instanceof ToolCallBlock tcb && "write_file".equals(tcb.toolName())) {
                    String path = tcb.input().get("path");
                    if (path != null) {
                        results.add("- Written: " + path);
                    }
                }
            }
        }
        if (results.isEmpty()) {
            return "No file outputs produced";
        }
        return String.join("\n", results);
    }

    private String extractWorklog(List<ConversationMessage> messages) {
        StringBuilder sb = new StringBuilder();
        int turn = 0;
        for (int i = 0; i < messages.size(); i++) {
            ConversationMessage msg = messages.get(i);
            if (msg.role() == MessageRole.ASSISTANT) {
                turn++;
                List<String> toolNames = new ArrayList<>();
                for (ContentBlock block : msg.blocks()) {
                    if (block instanceof ToolCallBlock tcb) {
                        toolNames.add(tcb.toolName());
                    }
                }
                String text = msg.plainText();
                String summary = text.length() > 80 ? text.substring(0, 80) + "..." : text;
                if (toolNames.isEmpty()) {
                    sb.append("Turn ").append(turn).append(": ").append(summary).append("\n");
                } else {
                    sb.append("Turn ").append(turn).append(": [")
                            .append(String.join(", ", toolNames))
                            .append("] ").append(summary).append("\n");
                }
                if (turn >= 20) {
                    sb.append("... earlier turns omitted\n");
                    break;
                }
            }
        }
        if (turn == 0) {
            return "No work performed yet";
        }
        return sb.toString();
    }

    private String truncateSection(String text) {
        if (text == null || text.isBlank()) {
            return "_Empty_";
        }
        int tokens = TokenEstimator.estimateTokens(text);
        if (tokens <= MAX_SECTION_TOKENS) {
            return text;
        }
        // Truncate at character level (tokens * ~2.5 chars per token as rough inverse)
        int maxChars = (int) (MAX_SECTION_TOKENS * 2.5);
        if (text.length() > maxChars) {
            // Find last newline before limit to truncate cleanly
            int cutPoint = text.lastIndexOf('\n', maxChars);
            if (cutPoint < maxChars / 2) cutPoint = maxChars;
            return text.substring(0, cutPoint) + "\n[... section truncated for length ...]";
        }
        return text;
    }
}
