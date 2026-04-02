package com.co.claudecode.demo.compact;

import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.message.MessageRole;
import com.co.claudecode.demo.message.SummaryBlock;
import com.co.claudecode.demo.message.TextBlock;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话内存压缩器——零 API 调用的压缩路径。
 * <p>
 * 对应 TS 原版 {@code sessionMemoryCompact.ts} 的 {@code i3Y()} 算法。
 * 当会话内存可用时，用已有的内存内容替代被切割的早期消息，
 * 无需调用 Claude API 生成摘要。
 * <p>
 * 阈值常量与 TS 原版 mp8 对象对齐：
 * - minTokens = 10,000
 * - minTextBlockMessages = 5
 * - maxTokens = 40,000
 */
public final class SessionMemoryCompactor {

    /** 最小累积 Token 数才触发（对应 TS mp8.minTokens）。 */
    public static final int MIN_TOKENS = 10_000;

    /** 最小文本消息数才触发（对应 TS mp8.minTextBlockMessages）。 */
    public static final int MIN_TEXT_BLOCK_MESSAGES = 5;

    /** 超过此值强制触发（对应 TS mp8.maxTokens）。 */
    public static final int MAX_TOKENS = 40_000;

    private SessionMemoryCompactor() {
    }

    /**
     * 尝试使用会话内存执行压缩。
     *
     * @param messages      当前消息列表
     * @param sessionMemory 会话内存实例
     * @return 压缩结果（type=NONE 表示不适用）
     */
    public static CompactResult trySessionMemoryCompact(List<ConversationMessage> messages,
                                                         SessionMemory sessionMemory) {
        // 前提：会话内存必须可用且非空
        if (sessionMemory == null || sessionMemory.isEmpty()) {
            return CompactResult.none(messages);
        }

        int tokensBefore = TokenEstimator.estimateTokens(messages);

        // 从末尾向前扫描，确定保留区
        int keepIndex = calculateKeepIndex(messages);
        if (keepIndex <= 0) {
            return CompactResult.none(messages);
        }

        // 确定 protectedPrefix（system 消息）
        int protectedPrefix = hasSystemHeader(messages) ? 1 : 0;
        if (keepIndex <= protectedPrefix) {
            return CompactResult.none(messages);
        }

        // 修正切割点：不在工具调用-结果对中间切断
        keepIndex = adjustCutPointForToolPairing(messages, protectedPrefix, keepIndex);
        if (keepIndex <= protectedPrefix) {
            return CompactResult.none(messages);
        }

        // 构建压缩后的消息列表
        String memoryContent = sessionMemory.getCurrentContent();
        String summaryText = "This session is being continued from a previous conversation that ran out of context.\n"
                + "The session memory below was extracted from the earlier portion:\n\n"
                + memoryContent;

        List<ConversationMessage> result = new ArrayList<>();
        // 1. 保留 system header
        result.addAll(messages.subList(0, protectedPrefix));
        // 2. 注入会话内存作为摘要
        result.add(new ConversationMessage(MessageRole.SYSTEM,
                List.of(new SummaryBlock(summaryText))));
        // 3. 保留尾部消息
        result.addAll(messages.subList(keepIndex, messages.size()));

        int tokensAfter = TokenEstimator.estimateTokens(result);
        int removed = keepIndex - protectedPrefix;

        return new CompactResult(result, CompactType.SESSION_MEMORY, removed,
                tokensBefore, tokensAfter,
                "Session memory compact: removed " + removed + " messages, saved ~" + (tokensBefore - tokensAfter) + " tokens");
    }

    /**
     * 从末尾向前扫描，计算保留区的起始索引。
     * <p>
     * 算法：累积 Token 计数，满足以下条件之一时停止：
     * 1. 累积 tokens >= MAX_TOKENS
     * 2. 累积 tokens >= MIN_TOKENS 且文本消息数 >= MIN_TEXT_BLOCK_MESSAGES
     */
    static int calculateKeepIndex(List<ConversationMessage> messages) {
        int accumulatedTokens = 0;
        int textMessageCount = 0;

        for (int i = messages.size() - 1; i >= 0; i--) {
            ConversationMessage msg = messages.get(i);
            accumulatedTokens += TokenEstimator.estimateTokens(msg);

            if (hasTextBlocks(msg)) {
                textMessageCount++;
            }

            // 强制停止：累积 Token 过多
            if (accumulatedTokens >= MAX_TOKENS) {
                return i;
            }

            // 正常停止：满足最小条件
            if (accumulatedTokens >= MIN_TOKENS && textMessageCount >= MIN_TEXT_BLOCK_MESSAGES) {
                return i;
            }
        }

        // 整个对话都不够阈值 → 不压缩
        return 0;
    }

    /**
     * 修正切割点，确保不在工具调用-结果对中间切断。
     * <p>
     * 对应 TS 原版 {@code B87()} 函数。
     * 如果切割点处的消息包含 tool_result，向前找到对应的 assistant 消息。
     */
    static int adjustCutPointForToolPairing(List<ConversationMessage> messages,
                                             int protectedPrefix,
                                             int cutPoint) {
        if (cutPoint <= protectedPrefix || cutPoint >= messages.size()) {
            return cutPoint;
        }

        ConversationMessage atCut = messages.get(cutPoint);

        // 如果切割点是 tool_result，需要向前找到对应的 assistant（with tool_use）
        if (!atCut.toolResults().isEmpty()) {
            for (int search = cutPoint - 1; search >= protectedPrefix; search--) {
                ConversationMessage candidate = messages.get(search);
                if (candidate.role() == MessageRole.ASSISTANT && !candidate.toolCalls().isEmpty()) {
                    return search;
                }
            }
            // 找不到匹配 → 不压缩
            return protectedPrefix;
        }

        return cutPoint;
    }

    private static boolean hasTextBlocks(ConversationMessage msg) {
        return msg.blocks().stream().anyMatch(TextBlock.class::isInstance);
    }

    private static boolean hasSystemHeader(List<ConversationMessage> messages) {
        return !messages.isEmpty() && messages.get(0).role() == MessageRole.SYSTEM;
    }
}
