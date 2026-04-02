package com.co.claudecode.demo.compact;

import com.co.claudecode.demo.message.ContentBlock;
import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.message.SummaryBlock;
import com.co.claudecode.demo.message.TextBlock;
import com.co.claudecode.demo.message.ToolCallBlock;
import com.co.claudecode.demo.message.ToolResultBlock;

import java.util.List;

/**
 * Token 估算器。
 * <p>
 * 对应 TS 原版 {@code Cj4()} 函数。
 * 由于没有实际的 tokenizer（tiktoken/sentencepiece），使用字符级启发式估算：
 * <ul>
 *   <li>纯 ASCII 文本：约 4 字符 = 1 token</li>
 *   <li>CJK 文本（中日韩）：约 1.5 字符 = 1 token</li>
 *   <li>混合文本：按字符分类累加</li>
 * </ul>
 * 精度不高但足以做预算判断，与 TS 原版的"粗略上界"策略一致。
 */
public final class TokenEstimator {

    /** 图片类内容的固定 Token 估算值。 */
    public static final int IMAGE_TOKEN_ESTIMATE = 2000;

    private TokenEstimator() {
    }

    /**
     * 估算文本的 Token 数。
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int asciiChars = 0;
        int cjkChars = 0;
        int otherChars = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch <= 0x7F) {
                asciiChars++;
            } else if (isCjk(ch)) {
                cjkChars++;
            } else {
                otherChars++;
            }
        }
        // ASCII: ~4 chars per token; CJK: ~1.5 chars per token; Other: ~2 chars per token
        double tokens = asciiChars / 4.0 + cjkChars / 1.5 + otherChars / 2.0;
        return Math.max(1, (int) Math.ceil(tokens));
    }

    /**
     * 估算单条消息的 Token 数（包括 role 标签开销）。
     */
    public static int estimateTokens(ConversationMessage message) {
        if (message == null) {
            return 0;
        }
        // role 标签开销约 4 token
        int tokens = 4;
        for (ContentBlock block : message.blocks()) {
            tokens += estimateBlockTokens(block);
        }
        return tokens;
    }

    /**
     * 估算消息列表的总 Token 数。
     */
    public static int estimateTokens(List<ConversationMessage> messages) {
        if (messages == null) {
            return 0;
        }
        int total = 0;
        for (ConversationMessage msg : messages) {
            total += estimateTokens(msg);
        }
        return total;
    }

    /**
     * 估算单个内容块的 Token 数。
     */
    public static int estimateBlockTokens(ContentBlock block) {
        if (block instanceof TextBlock textBlock) {
            return estimateTokens(textBlock.text());
        }
        if (block instanceof SummaryBlock summaryBlock) {
            return estimateTokens(summaryBlock.summary());
        }
        if (block instanceof ToolCallBlock toolCallBlock) {
            // tool name + input params
            int tokens = estimateTokens(toolCallBlock.toolName()) + 10; // JSON overhead
            for (var entry : toolCallBlock.input().entrySet()) {
                tokens += estimateTokens(entry.getKey()) + estimateTokens(entry.getValue());
            }
            return tokens;
        }
        if (block instanceof ToolResultBlock toolResultBlock) {
            return estimateToolResultTokens(toolResultBlock);
        }
        return 0;
    }

    /**
     * 估算工具结果 Token 数。
     */
    public static int estimateToolResultTokens(ToolResultBlock block) {
        if (block == null) {
            return 0;
        }
        // tool_use_id + tool_name overhead ~10 tokens
        return 10 + estimateTokens(block.content());
    }

    /**
     * 判断字符是否为 CJK 统一表意文字。
     */
    private static boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES;
    }
}
