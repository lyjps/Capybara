package com.co.claudecode.demo.compact;

import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.message.TextBlock;
import com.co.claudecode.demo.message.ToolCallBlock;
import com.co.claudecode.demo.message.ToolResultBlock;
import com.co.claudecode.demo.message.SummaryBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TokenEstimator 单元测试。
 */
class TokenEstimatorTest {

    // ---- estimateTokens(String) ----

    @Test
    void nullText_returnsZero() {
        assertEquals(0, TokenEstimator.estimateTokens((String) null));
    }

    @Test
    void emptyText_returnsZero() {
        assertEquals(0, TokenEstimator.estimateTokens(""));
    }

    @Test
    void pureAscii_roughlyFourCharsPerToken() {
        // "hello world" = 11 ASCII chars → ~11/4 = 2.75 → ceil = 3
        int tokens = TokenEstimator.estimateTokens("hello world");
        assertEquals(3, tokens);
    }

    @Test
    void pureCjk_roughlyOnePointFiveCharsPerToken() {
        // "你好世界测试" = 6 CJK chars → 6/1.5 = 4
        int tokens = TokenEstimator.estimateTokens("你好世界测试");
        assertEquals(4, tokens);
    }

    @Test
    void mixedText_combinesBothHeuristics() {
        // "Hello你好" = 5 ASCII + 2 CJK → 5/4 + 2/1.5 = 1.25 + 1.33 = 2.58 → ceil = 3
        int tokens = TokenEstimator.estimateTokens("Hello你好");
        assertTrue(tokens >= 2 && tokens <= 4, "Mixed text tokens should be ~3, got: " + tokens);
    }

    @Test
    void longText_scalesLinearly() {
        String shortText = "abcd"; // 1 token
        String longText = "abcd".repeat(100); // ~100 tokens
        int shortTokens = TokenEstimator.estimateTokens(shortText);
        int longTokens = TokenEstimator.estimateTokens(longText);
        assertTrue(longTokens > shortTokens * 50,
                "Long text should have proportionally more tokens");
    }

    @Test
    void singleChar_returnsAtLeastOne() {
        assertEquals(1, TokenEstimator.estimateTokens("a"));
    }

    // ---- estimateTokens(ConversationMessage) ----

    @Test
    void nullMessage_returnsZero() {
        assertEquals(0, TokenEstimator.estimateTokens((ConversationMessage) null));
    }

    @Test
    void simpleUserMessage_includesRoleOverhead() {
        ConversationMessage msg = ConversationMessage.user("test");
        int tokens = TokenEstimator.estimateTokens(msg);
        // role overhead (4) + "test" (1 token) = 5
        assertTrue(tokens >= 5, "Should include role overhead, got: " + tokens);
    }

    @Test
    void messageWithToolCall_estimatesInputParams() {
        ToolCallBlock tcb = new ToolCallBlock("id1", "read_file",
                Map.of("path", "/src/main/java/Foo.java"));
        ConversationMessage msg = ConversationMessage.assistant("Reading file", List.of(tcb));
        int tokens = TokenEstimator.estimateTokens(msg);
        assertTrue(tokens > 10, "Tool call message should have significant tokens, got: " + tokens);
    }

    @Test
    void messageWithToolResult_estimatesContent() {
        ToolResultBlock trb = new ToolResultBlock("id1", "read_file", false,
                "public class Foo { private int x; }");
        ConversationMessage msg = ConversationMessage.toolResult(trb);
        int tokens = TokenEstimator.estimateTokens(msg);
        assertTrue(tokens > 10, "Tool result message should have significant tokens, got: " + tokens);
    }

    // ---- estimateTokens(List<ConversationMessage>) ----

    @Test
    void nullList_returnsZero() {
        assertEquals(0, TokenEstimator.estimateTokens((List<ConversationMessage>) null));
    }

    @Test
    void emptyList_returnsZero() {
        assertEquals(0, TokenEstimator.estimateTokens(List.of()));
    }

    @Test
    void multipleMessages_sumsAll() {
        List<ConversationMessage> msgs = List.of(
                ConversationMessage.user("Hello"),
                ConversationMessage.assistant("Hi there", List.of()),
                ConversationMessage.user("How are you?")
        );
        int total = TokenEstimator.estimateTokens(msgs);
        int individual = msgs.stream().mapToInt(TokenEstimator::estimateTokens).sum();
        assertEquals(individual, total);
    }

    // ---- estimateBlockTokens ----

    @Test
    void textBlock_estimatesCorrectly() {
        TextBlock block = new TextBlock("Hello world");
        int tokens = TokenEstimator.estimateBlockTokens(block);
        assertEquals(TokenEstimator.estimateTokens("Hello world"), tokens);
    }

    @Test
    void summaryBlock_estimatesCorrectly() {
        SummaryBlock block = new SummaryBlock("Summary content here");
        int tokens = TokenEstimator.estimateBlockTokens(block);
        assertEquals(TokenEstimator.estimateTokens("Summary content here"), tokens);
    }

    // ---- estimateToolResultTokens ----

    @Test
    void nullToolResult_returnsZero() {
        assertEquals(0, TokenEstimator.estimateToolResultTokens(null));
    }

    @Test
    void toolResultWithContent_includesOverhead() {
        ToolResultBlock trb = new ToolResultBlock("id1", "read_file", false,
                "file content here");
        int tokens = TokenEstimator.estimateToolResultTokens(trb);
        // Should be 10 (overhead) + estimateTokens("file content here")
        int expected = 10 + TokenEstimator.estimateTokens("file content here");
        assertEquals(expected, tokens);
    }

    @Test
    void errorToolResult_sameEstimation() {
        ToolResultBlock trb = new ToolResultBlock("id1", "write_file", true,
                "Permission denied");
        int tokens = TokenEstimator.estimateToolResultTokens(trb);
        assertTrue(tokens > 10, "Error tool result should include content tokens");
    }
}
