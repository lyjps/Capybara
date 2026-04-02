package com.co.claudecode.demo.agent;

import com.co.claudecode.demo.compact.CompactResult;
import com.co.claudecode.demo.compact.CompactType;
import com.co.claudecode.demo.compact.FullCompactor;
import com.co.claudecode.demo.compact.MicroCompactConfig;
import com.co.claudecode.demo.compact.MicroCompactor;
import com.co.claudecode.demo.compact.SessionMemory;
import com.co.claudecode.demo.compact.SessionMemoryCompactor;
import com.co.claudecode.demo.compact.TokenEstimator;
import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.message.MessageRole;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 三级自适应上下文压缩的对话记忆管理。
 * <p>
 * 对应 TS 原版 {@code autoCompact.ts} 的 {@code hWK()} 压缩决策链。
 * 从基于消息计数触发改为 **Token 预算触发**，实现：
 * <ol>
 *   <li>Micro Compact — 清理过期工具结果</li>
 *   <li>Session Memory Compact — 用内存文件替代早期消息</li>
 *   <li>Full Compact — 结构化摘要生成</li>
 * </ol>
 * <p>
 * 核心阈值（与 TS 对齐）：
 * <pre>
 * autoCompactThreshold = contextWindowTokens - maxOutputTokens - autoCompactBuffer
 * 默认: 200,000 - 16,384 - 13,000 = 170,616 tokens
 * </pre>
 * <p>
 * 保留向后兼容的旧构造函数（基于消息计数），映射到新的 Token 预算参数。
 */
public final class ConversationMemory {

    // ---- Token 预算参数 ----

    /** 上下文窗口总大小（默认 200K）。 */
    private final int contextWindowTokens;

    /** 最大输出 Token（默认 16384，从有效窗口中扣除）。 */
    private final int maxOutputTokens;

    /** 自动压缩预留缓冲区（对应 TS U87 = 13000）。 */
    private final int autoCompactBuffer;

    /** 计算后的自动压缩阈值。 */
    private final int autoCompactThreshold;

    // ---- 压缩组件 ----

    private final SessionMemory sessionMemory;
    private final MicroCompactConfig microCompactConfig;

    // ---- 内部状态 ----

    private final List<ConversationMessage> messages = new ArrayList<>();
    private Instant lastAssistantTime;
    private int consecutiveCompactFailures = 0;
    private CompactResult lastCompactResult;

    // ---- 向后兼容参数 ----

    /** 旧模式：基于消息计数触发的阈值（仅向后兼容使用）。 */
    private final int legacyMaxMessages;
    private final int legacyTailSize;
    private final boolean useLegacyMode;

    /** 连续压缩失败熔断次数（对应 TS kDK = 3）。 */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    /**
     * 新构造——基于 Token 预算的三级压缩。
     *
     * @param contextWindowTokens 上下文窗口总 Token 数（如 200_000）
     * @param maxOutputTokens     最大输出 Token 数（如 16_384）
     * @param autoCompactBuffer   自动压缩预留缓冲区（如 13_000）
     * @param sessionMemory       会话内存实例（可为 null）
     * @param microCompactConfig  微压缩配置
     */
    public ConversationMemory(int contextWindowTokens,
                              int maxOutputTokens,
                              int autoCompactBuffer,
                              SessionMemory sessionMemory,
                              MicroCompactConfig microCompactConfig) {
        this.contextWindowTokens = contextWindowTokens;
        this.maxOutputTokens = Math.min(maxOutputTokens, 20_000); // cap at 20K like TS
        this.autoCompactBuffer = autoCompactBuffer;
        this.autoCompactThreshold = contextWindowTokens - this.maxOutputTokens - autoCompactBuffer;
        this.sessionMemory = sessionMemory;
        this.microCompactConfig = microCompactConfig != null ? microCompactConfig : MicroCompactConfig.DEFAULT;
        this.useLegacyMode = false;
        this.legacyMaxMessages = 0;
        this.legacyTailSize = 0;
    }

    /**
     * 向后兼容构造——基于消息计数的旧模式。
     * <p>
     * 旧代码传入 {@code SimpleContextCompactor} 的地方继续工作，
     * 但内部使用 {@code FullCompactor} 替代。
     */
    @SuppressWarnings("unused")
    public ConversationMemory(SimpleContextCompactor compactor,
                              int maxMutableMessagesBeforeCompact,
                              int preservedTailSize) {
        this.contextWindowTokens = 200_000;
        this.maxOutputTokens = 16_384;
        this.autoCompactBuffer = 13_000;
        this.autoCompactThreshold = contextWindowTokens - maxOutputTokens - autoCompactBuffer;
        this.sessionMemory = null;
        this.microCompactConfig = MicroCompactConfig.DEFAULT;
        this.useLegacyMode = true;
        this.legacyMaxMessages = maxMutableMessagesBeforeCompact;
        this.legacyTailSize = preservedTailSize;
    }

    /**
     * 追加消息，如果触发了压缩返回压缩结果。
     *
     * @return 压缩结果，null 表示未压缩
     */
    public CompactResult appendAndCompact(ConversationMessage message) {
        messages.add(message);
        if (message.role() == MessageRole.ASSISTANT) {
            lastAssistantTime = Instant.now();
        }
        return maybeCompact();
    }

    /**
     * 追加消息（向后兼容，返回 boolean）。
     */
    public boolean append(ConversationMessage message) {
        CompactResult result = appendAndCompact(message);
        return result != null && result.didCompact();
    }

    /**
     * 返回当前消息快照。
     */
    public List<ConversationMessage> snapshot() {
        return List.copyOf(messages);
    }

    /**
     * 当前消息数量。
     */
    public int size() {
        return messages.size();
    }

    /**
     * 估算当前总 Token 数。
     */
    public int estimateCurrentTokens() {
        return TokenEstimator.estimateTokens(messages);
    }

    /**
     * 返回自动压缩阈值。
     */
    public int getAutoCompactThreshold() {
        return autoCompactThreshold;
    }

    /**
     * 返回上次压缩结果（可为 null）。
     */
    public CompactResult getLastCompactResult() {
        return lastCompactResult;
    }

    /**
     * 返回会话内存实例（可为 null）。
     */
    public SessionMemory getSessionMemory() {
        return sessionMemory;
    }

    /**
     * 手动触发压缩。
     */
    public CompactResult forceCompact() {
        return executeCompaction();
    }

    /**
     * 手动触发微型压缩。
     */
    public CompactResult forceMicroCompact() {
        CompactResult result = MicroCompactor.forceCleanup(messages, microCompactConfig.keepRecent());
        if (result.didCompact()) {
            messages.clear();
            messages.addAll(result.messages());
            lastCompactResult = result;
        }
        return result;
    }

    /**
     * 获取 Token 用量统计。
     */
    public ContextStats getContextStats() {
        int currentTokens = estimateCurrentTokens();
        double usedPercentage = contextWindowTokens > 0
                ? (currentTokens * 100.0) / contextWindowTokens : 0;
        return new ContextStats(
                messages.size(),
                currentTokens,
                contextWindowTokens,
                autoCompactThreshold,
                usedPercentage,
                lastCompactResult
        );
    }

    // ---- 内部压缩逻辑 ----

    private CompactResult maybeCompact() {
        if (useLegacyMode) {
            return maybeCompactLegacy();
        }
        return maybeCompactTokenBased();
    }

    /**
     * Token 预算驱动的三级压缩决策链。
     */
    private CompactResult maybeCompactTokenBased() {
        int currentTokens = estimateCurrentTokens();

        // 未达阈值 → 不压缩
        if (currentTokens < autoCompactThreshold) {
            return null;
        }

        // 熔断保护：连续失败过多次则停止尝试
        if (consecutiveCompactFailures >= MAX_CONSECUTIVE_FAILURES) {
            return null;
        }

        return executeCompaction();
    }

    /**
     * 执行压缩（三级决策链）。
     */
    private CompactResult executeCompaction() {
        // Level 1: 尝试 Micro Compact
        CompactResult microResult = MicroCompactor.tryMicroCompact(
                messages, lastAssistantTime, microCompactConfig);
        if (microResult.didCompact()) {
            applyCompactResult(microResult);
            // 微压缩后检查是否仍然超阈值
            if (estimateCurrentTokens() < autoCompactThreshold) {
                return microResult;
            }
        }

        // Level 2: 尝试 Session Memory Compact
        if (sessionMemory != null) {
            // 先更新会话内存（从当前对话提取）
            sessionMemory.extractMemoryFromConversation(messages);

            CompactResult smResult = SessionMemoryCompactor.trySessionMemoryCompact(messages, sessionMemory);
            if (smResult.didCompact()) {
                applyCompactResult(smResult);
                return smResult;
            }
        }

        // Level 3: Full Compact
        CompactResult fullResult = executeFullCompact();
        if (fullResult.didCompact()) {
            applyCompactResult(fullResult);
            // 压缩成功后更新会话内存
            if (sessionMemory != null) {
                sessionMemory.extractMemoryFromConversation(messages);
            }
            return fullResult;
        }

        // 所有策略都失败
        consecutiveCompactFailures++;
        return null;
    }

    private CompactResult executeFullCompact() {
        int protectedPrefix = hasSystemHeader() ? 1 : 0;

        // 计算保留尾部大小：保留约 40% 的消息或至少最后 8 条
        int tailSize = Math.max(8, messages.size() * 2 / 5);
        int tailStart = Math.max(protectedPrefix + 1, messages.size() - tailSize);

        if (tailStart <= protectedPrefix) {
            return CompactResult.none(messages);
        }

        // 修正切割点：不拆分工具配对
        tailStart = adjustTailForToolPairing(protectedPrefix, tailStart);
        if (tailStart <= protectedPrefix) {
            return CompactResult.none(messages);
        }

        return FullCompactor.fullCompact(messages, protectedPrefix, tailStart);
    }

    /**
     * 旧模式：基于消息计数的压缩（向后兼容）。
     */
    private CompactResult maybeCompactLegacy() {
        int protectedPrefix = hasSystemHeader() ? 1 : 0;
        int mutableSize = messages.size() - protectedPrefix;
        if (mutableSize <= legacyMaxMessages) {
            return null;
        }

        int tailStart = Math.max(protectedPrefix, messages.size() - legacyTailSize);
        if (tailStart <= protectedPrefix) {
            return null;
        }

        tailStart = adjustTailForToolPairing(protectedPrefix, tailStart);
        if (tailStart <= protectedPrefix) {
            return null;
        }

        CompactResult result = FullCompactor.fullCompact(messages, protectedPrefix, tailStart);
        if (result.didCompact()) {
            applyCompactResult(result);
        }
        return result;
    }

    private void applyCompactResult(CompactResult result) {
        messages.clear();
        messages.addAll(result.messages());
        consecutiveCompactFailures = 0;
        lastCompactResult = result;
    }

    /**
     * 向前调整 tailStart，确保不拆分 tool_use/tool_result 配对。
     * <p>
     * 改进版：递归处理多层嵌套。
     */
    private int adjustTailForToolPairing(int protectedPrefix, int tailStart) {
        int maxIterations = 10; // 防止无限循环
        int iterations = 0;
        while (tailStart > protectedPrefix && iterations < maxIterations) {
            ConversationMessage atTail = messages.get(tailStart);
            if (!atTail.toolResults().isEmpty()) {
                // 往前找对应的 assistant（含 tool_use）
                int search = tailStart - 1;
                boolean found = false;
                while (search >= protectedPrefix) {
                    ConversationMessage candidate = messages.get(search);
                    if (candidate.role() == MessageRole.ASSISTANT && !candidate.toolCalls().isEmpty()) {
                        tailStart = search;
                        found = true;
                        break;
                    }
                    search--;
                }
                if (!found) {
                    tailStart = protectedPrefix;
                }
                iterations++;
            } else {
                break;
            }
        }
        return tailStart;
    }

    private boolean hasSystemHeader() {
        return !messages.isEmpty() && messages.get(0).role() == MessageRole.SYSTEM;
    }

    /**
     * 上下文使用统计。
     */
    public record ContextStats(
            int messageCount,
            int currentTokens,
            int contextWindowTokens,
            int autoCompactThreshold,
            double usedPercentage,
            CompactResult lastCompactResult
    ) {
        public String format() {
            StringBuilder sb = new StringBuilder();
            sb.append("Context usage:\n");
            sb.append("  Messages:    ").append(messageCount).append("\n");
            sb.append("  Tokens:      ").append(currentTokens).append(" / ").append(contextWindowTokens).append("\n");
            sb.append("  Used:        ").append(String.format("%.1f%%", usedPercentage)).append("\n");
            sb.append("  Compact at:  ").append(autoCompactThreshold).append(" tokens\n");
            if (lastCompactResult != null && lastCompactResult.didCompact()) {
                sb.append("  Last compact: ").append(lastCompactResult.type())
                        .append(", saved ").append(lastCompactResult.tokensSaved()).append(" tokens\n");
            }
            return sb.toString();
        }
    }
}
