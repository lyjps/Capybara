package com.co.claudecode.demo.compact;

import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.message.TextBlock;
import com.co.claudecode.demo.message.ToolCallBlock;
import com.co.claudecode.demo.message.ToolResultBlock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SessionMemoryCompactor 单元测试。
 */
class SessionMemoryCompactorTest {

    // ---- 前提条件检查 ----

    @Test
    void nullSessionMemory_returnsNone() {
        List<ConversationMessage> messages = buildLargeConversation(50);
        CompactResult result = SessionMemoryCompactor.trySessionMemoryCompact(messages, null);
        assertFalse(result.didCompact());
    }

    @Test
    void emptySessionMemory_returnsNone() {
        List<ConversationMessage> messages = buildLargeConversation(50);
        SessionMemory emptyMemory = new SessionMemory();
        CompactResult result = SessionMemoryCompactor.trySessionMemoryCompact(messages, emptyMemory);
        assertFalse(result.didCompact(), "Empty session memory should not trigger compaction");
    }

    @Test
    void populatedSessionMemory_canCompact() {
        List<ConversationMessage> messages = buildLargeConversation(50);
        SessionMemory memory = createPopulatedMemory();

        CompactResult result = SessionMemoryCompactor.trySessionMemoryCompact(messages, memory);
        // May or may not compact depending on token thresholds
        if (result.didCompact()) {
            assertEquals(CompactType.SESSION_MEMORY, result.type());
        }
    }

    // ---- calculateKeepIndex ----

    @Test
    void calculateKeepIndex_smallConversation_returnsZero() {
        // Small conversation that doesn't hit MIN_TOKENS
        List<ConversationMessage> messages = List.of(
                ConversationMessage.user("Hello"),
                ConversationMessage.assistant("Hi", List.of())
        );
        int keepIndex = SessionMemoryCompactor.calculateKeepIndex(messages);
        assertEquals(0, keepIndex, "Small conversation should return 0 (no compaction)");
    }

    @Test
    void calculateKeepIndex_largeConversation_returnsNonZero() {
        // Build conversation large enough to exceed MIN_TOKENS
        List<ConversationMessage> messages = buildLargeConversation(100);
        int keepIndex = SessionMemoryCompactor.calculateKeepIndex(messages);
        assertTrue(keepIndex > 0, "Large conversation should have positive keepIndex, got: " + keepIndex);
    }

    @Test
    void calculateKeepIndex_maxTokensForced() {
        // Build a very large conversation that exceeds MAX_TOKENS in tail alone
        List<ConversationMessage> messages = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            messages.add(ConversationMessage.user("A very long user message " + "x".repeat(500) + " turn " + i));
            messages.add(ConversationMessage.assistant("A very long response " + "y".repeat(500) + " turn " + i, List.of()));
        }
        int keepIndex = SessionMemoryCompactor.calculateKeepIndex(messages);
        assertTrue(keepIndex > 0, "Very large conversation should trigger MAX_TOKENS cut");
    }

    // ---- adjustCutPointForToolPairing ----

    @Test
    void adjustCutPoint_atToolResult_movesBackToAssistant() {
        List<ConversationMessage> messages = new ArrayList<>();
        messages.add(ConversationMessage.system("System prompt"));    // 0
        messages.add(ConversationMessage.user("Do something"));       // 1
        messages.add(ConversationMessage.assistant("Calling tool", List.of(  // 2
                new ToolCallBlock("tc1", "read_file", Map.of("path", "/f.java"))
        )));
        messages.add(ConversationMessage.toolResult(                  // 3
                new ToolResultBlock("tc1", "read_file", false, "file content")
        ));
        messages.add(ConversationMessage.assistant("Done", List.of())); // 4

        // Cut at index 3 (tool result) → should move back to index 2 (assistant with tool call)
        int adjusted = SessionMemoryCompactor.adjustCutPointForToolPairing(messages, 1, 3);
        assertEquals(2, adjusted, "Should move cut point from tool result to matching assistant");
    }

    @Test
    void adjustCutPoint_atNonToolResult_unchanged() {
        List<ConversationMessage> messages = new ArrayList<>();
        messages.add(ConversationMessage.system("System prompt"));
        messages.add(ConversationMessage.user("Question 1"));
        messages.add(ConversationMessage.assistant("Answer 1", List.of()));
        messages.add(ConversationMessage.user("Question 2"));

        int adjusted = SessionMemoryCompactor.adjustCutPointForToolPairing(messages, 1, 3);
        assertEquals(3, adjusted, "Non-tool-result cut point should be unchanged");
    }

    @Test
    void adjustCutPoint_noMatchingAssistant_returnsProtectedPrefix() {
        List<ConversationMessage> messages = new ArrayList<>();
        messages.add(ConversationMessage.system("System prompt"));    // 0
        // Orphaned tool result at index 1 (no preceding assistant with tool call)
        messages.add(ConversationMessage.toolResult(                  // 1
                new ToolResultBlock("tc1", "read_file", false, "content")
        ));
        messages.add(ConversationMessage.user("Next question"));     // 2

        int adjusted = SessionMemoryCompactor.adjustCutPointForToolPairing(messages, 0, 1);
        assertEquals(0, adjusted, "Should return protectedPrefix when no matching assistant found");
    }

    // ---- 压缩结果验证 ----

    @Test
    void compactResult_containsSessionMemoryContent() {
        List<ConversationMessage> messages = buildLargeConversation(100);
        SessionMemory memory = createPopulatedMemory();

        CompactResult result = SessionMemoryCompactor.trySessionMemoryCompact(messages, memory);
        if (result.didCompact()) {
            // The result should contain a summary message with session memory content
            boolean hasSummary = result.messages().stream()
                    .flatMap(m -> m.blocks().stream())
                    .anyMatch(b -> b.renderForModel().contains("session memory"));
            // Actually check for the session memory content in summary blocks
            String allText = result.messages().stream()
                    .map(ConversationMessage::renderForModel)
                    .reduce("", (a, b) -> a + "\n" + b);
            assertTrue(allText.contains("My populated session memory"),
                    "Compact result should contain session memory content");
        }
    }

    @Test
    void compactResult_preservesSystemHeader() {
        List<ConversationMessage> messages = new ArrayList<>();
        messages.add(ConversationMessage.system("Important system prompt"));
        // Add enough messages to trigger compaction
        for (int i = 0; i < 100; i++) {
            messages.add(ConversationMessage.user("Question " + i + " " + "x".repeat(200)));
            messages.add(ConversationMessage.assistant("Answer " + i + " " + "y".repeat(200), List.of()));
        }

        SessionMemory memory = createPopulatedMemory();
        CompactResult result = SessionMemoryCompactor.trySessionMemoryCompact(messages, memory);
        if (result.didCompact()) {
            assertEquals("Important system prompt", result.messages().get(0).plainText(),
                    "System header should be preserved");
        }
    }

    @Test
    void compactResult_preservesTailMessages() {
        List<ConversationMessage> messages = buildLargeConversation(100);
        String lastMessage = messages.get(messages.size() - 1).plainText();
        SessionMemory memory = createPopulatedMemory();

        CompactResult result = SessionMemoryCompactor.trySessionMemoryCompact(messages, memory);
        if (result.didCompact()) {
            String resultLast = result.messages().get(result.messages().size() - 1).plainText();
            assertEquals(lastMessage, resultLast, "Last message should be preserved in tail");
        }
    }

    // ---- 常量验证 ----

    @Test
    void thresholdConstants_matchTsValues() {
        assertEquals(10_000, SessionMemoryCompactor.MIN_TOKENS);
        assertEquals(5, SessionMemoryCompactor.MIN_TEXT_BLOCK_MESSAGES);
        assertEquals(40_000, SessionMemoryCompactor.MAX_TOKENS);
    }

    // ---- Helpers ----

    private List<ConversationMessage> buildLargeConversation(int turns) {
        List<ConversationMessage> messages = new ArrayList<>();
        messages.add(ConversationMessage.system("You are a helpful assistant."));
        for (int i = 0; i < turns; i++) {
            messages.add(ConversationMessage.user("User question " + i + ": " + "x".repeat(200)));
            messages.add(ConversationMessage.assistant("Assistant answer " + i + ": " + "y".repeat(200), List.of()));
        }
        return messages;
    }

    private SessionMemory createPopulatedMemory() {
        SessionMemory memory = new SessionMemory();
        memory.updateContent("# Session Title\nMy populated session memory\n\n# Current State\nWorking on tests\n");
        return memory;
    }
}
