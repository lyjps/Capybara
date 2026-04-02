package com.co.claudecode.demo.compact;

import com.co.claudecode.demo.message.ContentBlock;
import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.message.MessageRole;
import com.co.claudecode.demo.message.ToolResultBlock;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 微型压缩器——清理过期的大型工具结果。
 * <p>
 * 对应 TS 原版 {@code microCompact.ts} 的 {@code Hd_()} 函数。
 * 不同于全对话摘要，微压缩只替换单个工具结果的内容，
 * 保留消息结构不变。
 * <p>
 * 触发条件：
 * <ul>
 *   <li>功能已启用</li>
 *   <li>最后一条 assistant 消息距今超过 gapThresholdMinutes</li>
 * </ul>
 */
public final class MicroCompactor {

    /** 可清理的工具类型集合。 */
    private static final Set<String> CLEARABLE_TOOLS = Set.of(
            "list_files", "read_file", "write_file"
    );

    private MicroCompactor() {
    }

    /**
     * 尝试执行微型压缩。
     *
     * @param messages          当前消息列表
     * @param lastAssistantTime 最后一条 assistant 消息的时间
     * @param config            微压缩配置
     * @return 压缩结果（type=NONE 表示未触发）
     */
    public static CompactResult tryMicroCompact(List<ConversationMessage> messages,
                                                 Instant lastAssistantTime,
                                                 MicroCompactConfig config) {
        if (!config.enabled()) {
            return CompactResult.none(messages);
        }
        if (lastAssistantTime == null) {
            return CompactResult.none(messages);
        }

        Duration gap = Duration.between(lastAssistantTime, Instant.now());
        if (gap.toMinutes() < config.gapThresholdMinutes()) {
            return CompactResult.none(messages);
        }

        return executeCleanup(messages, config.keepRecent());
    }

    /**
     * 强制执行微型压缩（不检查时间间隔）。
     */
    public static CompactResult forceCleanup(List<ConversationMessage> messages, int keepRecent) {
        return executeCleanup(messages, keepRecent);
    }

    private static CompactResult executeCleanup(List<ConversationMessage> messages, int keepRecent) {
        // 1. 收集所有可清理的工具结果 ID（按出现顺序）
        LinkedHashSet<ToolResultLocation> clearableResults = new LinkedHashSet<>();
        for (int i = 0; i < messages.size(); i++) {
            ConversationMessage msg = messages.get(i);
            for (ContentBlock block : msg.blocks()) {
                if (block instanceof ToolResultBlock trb && CLEARABLE_TOOLS.contains(trb.toolName())) {
                    clearableResults.add(new ToolResultLocation(i, trb.toolUseId(), trb.toolName()));
                }
            }
        }

        if (clearableResults.size() <= keepRecent) {
            return CompactResult.none(messages);
        }

        // 2. 保留最近 keepRecent 个，其余标记为待清理
        List<ToolResultLocation> allResults = new ArrayList<>(clearableResults);
        int clearCount = allResults.size() - keepRecent;
        Set<String> toClearIds = new LinkedHashSet<>();
        for (int i = 0; i < clearCount; i++) {
            toClearIds.add(allResults.get(i).toolUseId());
        }

        // 3. 执行替换
        int tokensBefore = TokenEstimator.estimateTokens(messages);
        List<ConversationMessage> result = new ArrayList<>();
        int cleared = 0;
        for (ConversationMessage msg : messages) {
            if (msg.role() == MessageRole.USER && hasToolResults(msg)) {
                List<ContentBlock> newBlocks = new ArrayList<>();
                for (ContentBlock block : msg.blocks()) {
                    if (block instanceof ToolResultBlock trb && toClearIds.contains(trb.toolUseId())) {
                        int savedTokens = TokenEstimator.estimateToolResultTokens(trb);
                        newBlocks.add(new ToolResultBlock(
                                trb.toolUseId(), trb.toolName(), false,
                                "[Old tool result content cleared — saved ~" + savedTokens + " tokens]"
                        ));
                        cleared++;
                    } else {
                        newBlocks.add(block);
                    }
                }
                result.add(new ConversationMessage(msg.role(), newBlocks));
            } else {
                result.add(msg);
            }
        }

        int tokensAfter = TokenEstimator.estimateTokens(result);
        return new CompactResult(result, CompactType.MICRO, 0, tokensBefore, tokensAfter,
                "Micro compact: cleared " + cleared + " old tool results, saved ~" + (tokensBefore - tokensAfter) + " tokens");
    }

    private static boolean hasToolResults(ConversationMessage msg) {
        return msg.blocks().stream().anyMatch(ToolResultBlock.class::isInstance);
    }

    private record ToolResultLocation(int messageIndex, String toolUseId, String toolName) {
    }
}
