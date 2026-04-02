package com.co.claudecode.demo.compact;

import com.co.claudecode.demo.message.ContentBlock;
import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.message.MessageRole;
import com.co.claudecode.demo.message.SummaryBlock;
import com.co.claudecode.demo.message.TextBlock;
import com.co.claudecode.demo.message.ToolCallBlock;
import com.co.claudecode.demo.message.ToolResultBlock;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 完整压缩器——结构化摘要生成。
 * <p>
 * 替代 {@code SimpleContextCompactor}，对应 TS 原版 {@code compact.ts} 的 LE6() 函数。
 * <p>
 * 与旧实现的区别：
 * <ul>
 *   <li>结构化分类提取（用户意图/工具轨迹/关键决策/文件引用），而非简单截断</li>
 *   <li>输出格式与 TS 原版 P18() 函数对齐</li>
 *   <li>压缩后恢复关键附件（最近读取的文件路径等）</li>
 *   <li>不限制消息数量上限，处理所有被压缩的消息</li>
 * </ul>
 */
public final class FullCompactor {

    /** 压缩后恢复的文件引用最多 5 个（对应 TS eWK = 5）。 */
    public static final int MAX_RESTORED_FILES = 5;

    private FullCompactor() {
    }

    /**
     * 对一组消息执行完整压缩，生成结构化摘要。
     *
     * @param messagesToCompact 需要压缩的消息列表
     * @return 压缩后的摘要消息（SYSTEM 角色，SummaryBlock）
     */
    public static ConversationMessage compact(List<ConversationMessage> messagesToCompact) {
        if (messagesToCompact == null || messagesToCompact.isEmpty()) {
            return new ConversationMessage(MessageRole.SYSTEM,
                    List.of(new SummaryBlock("(No prior conversation context)")));
        }

        String userIntent = extractUserIntent(messagesToCompact);
        String toolTrace = extractToolTrace(messagesToCompact);
        String keyDecisions = extractKeyDecisions(messagesToCompact);
        String recentFiles = extractRecentFiles(messagesToCompact);
        String unfinishedWork = extractUnfinishedWork(messagesToCompact);

        StringBuilder summary = new StringBuilder();
        summary.append("This session is being continued from a previous conversation that ran out of context.\n");
        summary.append("The summary below covers the earlier portion of the conversation.\n\n");
        summary.append("<summary>\n");

        summary.append("## User Intent\n");
        summary.append(userIntent).append("\n\n");

        if (!toolTrace.isBlank()) {
            summary.append("## Tool Call Trace\n");
            summary.append(toolTrace).append("\n\n");
        }

        if (!keyDecisions.isBlank()) {
            summary.append("## Key Decisions\n");
            summary.append(keyDecisions).append("\n\n");
        }

        if (!unfinishedWork.isBlank()) {
            summary.append("## Unfinished Work\n");
            summary.append(unfinishedWork).append("\n\n");
        }

        if (!recentFiles.isBlank()) {
            summary.append("## Recently Accessed Files\n");
            summary.append(recentFiles).append("\n");
        }

        summary.append("</summary>\n\n");
        summary.append("Continue the conversation from where it left off without asking the user any further questions. ");
        summary.append("Resume directly — do not acknowledge the summary, do not recap what was happening, ");
        summary.append("do not preface with \"I'll continue\" or similar. Pick up the last task as if the break never happened.");

        return new ConversationMessage(MessageRole.SYSTEM,
                List.of(new SummaryBlock(summary.toString())));
    }

    /**
     * 执行完整压缩流程，返回统一结果。
     */
    public static CompactResult fullCompact(List<ConversationMessage> allMessages,
                                             int protectedPrefix,
                                             int tailStart) {
        int tokensBefore = TokenEstimator.estimateTokens(allMessages);

        List<ConversationMessage> toCompact = new ArrayList<>(
                allMessages.subList(protectedPrefix, tailStart));
        ConversationMessage summaryMessage = compact(toCompact);

        List<ConversationMessage> result = new ArrayList<>();
        result.addAll(allMessages.subList(0, protectedPrefix));
        result.add(summaryMessage);
        result.addAll(allMessages.subList(tailStart, allMessages.size()));

        int tokensAfter = TokenEstimator.estimateTokens(result);
        int removed = tailStart - protectedPrefix;

        return new CompactResult(result, CompactType.FULL, removed,
                tokensBefore, tokensAfter,
                "Full compact: removed " + removed + " messages, saved ~" + (tokensBefore - tokensAfter) + " tokens");
    }

    // ---- 提取方法 ----

    private static String extractUserIntent(List<ConversationMessage> messages) {
        List<String> intents = new ArrayList<>();
        for (ConversationMessage msg : messages) {
            if (msg.role() == MessageRole.USER) {
                String text = msg.plainText();
                if (!text.isBlank() && !text.startsWith("[Message from another agent]")) {
                    String shortened = text.length() > 300 ? text.substring(0, 300) + "..." : text;
                    intents.add("- " + shortened);
                }
            }
        }
        if (intents.isEmpty()) {
            return "No explicit user requests in this segment.";
        }
        // Keep at most 5 user intents
        if (intents.size() > 5) {
            intents = intents.subList(intents.size() - 5, intents.size());
            intents.add(0, "... (earlier requests omitted)");
        }
        return String.join("\n", intents);
    }

    private static String extractToolTrace(List<ConversationMessage> messages) {
        List<String> traces = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            ConversationMessage msg = messages.get(i);
            for (ContentBlock block : msg.blocks()) {
                if (block instanceof ToolCallBlock tcb) {
                    String params = summarizeParams(tcb);
                    String resultSummary = findResultSummary(messages, i, tcb.id());
                    traces.add(tcb.toolName() + "(" + params + ")" +
                            (resultSummary.isEmpty() ? "" : " → " + resultSummary));
                }
            }
        }
        if (traces.isEmpty()) {
            return "";
        }
        // Keep at most 20 traces
        int start = Math.max(0, traces.size() - 20);
        if (start > 0) {
            traces = new ArrayList<>(traces.subList(start, traces.size()));
            traces.add(0, "... (" + start + " earlier tool calls omitted)");
        }
        return String.join("\n", traces);
    }

    private static String extractKeyDecisions(List<ConversationMessage> messages) {
        List<String> decisions = new ArrayList<>();
        for (ConversationMessage msg : messages) {
            if (msg.role() == MessageRole.ASSISTANT) {
                String text = msg.plainText();
                if (!text.isBlank() && text.length() > 20) {
                    String shortened = text.length() > 200 ? text.substring(0, 200) + "..." : text;
                    decisions.add("- " + shortened);
                }
            }
        }
        if (decisions.isEmpty()) {
            return "";
        }
        // Keep at most 5 key decisions
        if (decisions.size() > 5) {
            decisions = new ArrayList<>(decisions.subList(decisions.size() - 5, decisions.size()));
            decisions.add(0, "... (earlier decisions omitted)");
        }
        return String.join("\n", decisions);
    }

    private static String extractRecentFiles(List<ConversationMessage> messages) {
        // Collect last N unique file paths from read_file / write_file calls
        LinkedHashSet<String> allFiles = new LinkedHashSet<>();
        for (ConversationMessage msg : messages) {
            for (ContentBlock block : msg.blocks()) {
                if (block instanceof ToolCallBlock tcb) {
                    if ("read_file".equals(tcb.toolName()) || "write_file".equals(tcb.toolName())) {
                        String path = tcb.input().get("path");
                        if (path != null && !path.isBlank()) {
                            allFiles.add(path);
                        }
                    }
                }
            }
        }
        if (allFiles.isEmpty()) {
            return "";
        }
        // Keep at most MAX_RESTORED_FILES
        List<String> fileList = new ArrayList<>(allFiles);
        int start = Math.max(0, fileList.size() - MAX_RESTORED_FILES);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < fileList.size(); i++) {
            sb.append("- ").append(fileList.get(i)).append("\n");
        }
        return sb.toString();
    }

    private static String extractUnfinishedWork(List<ConversationMessage> messages) {
        if (messages.isEmpty()) {
            return "";
        }
        // Check if the last assistant message had tool calls (indicating unfinished work)
        for (int i = messages.size() - 1; i >= 0; i--) {
            ConversationMessage msg = messages.get(i);
            if (msg.role() == MessageRole.ASSISTANT && !msg.toolCalls().isEmpty()) {
                List<String> pendingTools = new ArrayList<>();
                for (ToolCallBlock tcb : msg.toolCalls()) {
                    pendingTools.add(tcb.toolName() + "(" + summarizeParams(tcb) + ")");
                }
                return "Last assistant turn had pending tool calls:\n" +
                        String.join("\n", pendingTools.stream().map(s -> "- " + s).toList());
            }
            if (msg.role() == MessageRole.ASSISTANT) {
                break; // Last assistant had no tool calls → nothing pending
            }
        }
        return "";
    }

    private static String summarizeParams(ToolCallBlock tcb) {
        if (tcb.input().isEmpty()) {
            return "";
        }
        // Show path if present, otherwise first param
        String path = tcb.input().get("path");
        if (path != null) {
            return path;
        }
        var entry = tcb.input().entrySet().iterator().next();
        String value = entry.getValue();
        if (value.length() > 40) {
            value = value.substring(0, 40) + "...";
        }
        return entry.getKey() + "=" + value;
    }

    private static String findResultSummary(List<ConversationMessage> messages, int fromIndex, String toolCallId) {
        for (int i = fromIndex + 1; i < messages.size(); i++) {
            ConversationMessage msg = messages.get(i);
            for (ContentBlock block : msg.blocks()) {
                if (block instanceof ToolResultBlock trb && trb.toolUseId().equals(toolCallId)) {
                    if (trb.error()) {
                        String content = trb.content();
                        return "ERROR: " + (content.length() > 60 ? content.substring(0, 60) + "..." : content);
                    }
                    String content = trb.content();
                    if (content.length() > 60) {
                        return content.substring(0, 60) + "...";
                    }
                    return content;
                }
            }
        }
        return "";
    }
}
