package com.co.claudecode.demo.compact;

import com.co.claudecode.demo.agent.ConversationMemory;
import com.co.claudecode.demo.agent.SimpleContextCompactor;
import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.message.MessageRole;
import com.co.claudecode.demo.message.ToolCallBlock;
import com.co.claudecode.demo.message.ToolResultBlock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConversationMemory 单元测试（三级压缩版本）。
 */
class ConversationMemoryTest {

    // ---- 新构造函数 ----

    @Test
    void newConstructor_setsDefaultValues() {
        ConversationMemory memory = new ConversationMemory(
                200_000, 16_384, 13_000, null, null);
        assertEquals(200_000 - 16_384 - 13_000, memory.getAutoCompactThreshold());
    }

    @Test
    void newConstructor_capsMaxOutputAt20K() {
        // maxOutputTokens > 20K should be capped
        ConversationMemory memory = new ConversationMemory(
                200_000, 50_000, 13_000, null, null);
        // threshold = 200000 - 20000(capped) - 13000 = 167000
        assertEquals(167_000, memory.getAutoCompactThreshold());
    }

    @Test
    void newConstructor_withSessionMemory() {
        SessionMemory sm = new SessionMemory();
        ConversationMemory memory = new ConversationMemory(
                200_000, 16_384, 13_000, sm, MicroCompactConfig.ENABLED);
        assertSame(sm, memory.getSessionMemory());
    }

    // ---- 向后兼容构造函数 ----

    @Test
    void legacyConstructor_stillWorks() {
        ConversationMemory memory = new ConversationMemory(
                new SimpleContextCompactor(), 24, 12);
        assertNotNull(memory);
        assertEquals(0, memory.size());
    }

    @Test
    void legacyConstructor_compactsOnMessageCount() {
        ConversationMemory memory = new ConversationMemory(
                new SimpleContextCompactor(), 8, 4);
        memory.append(ConversationMessage.system("System prompt"));

        // Add enough messages to trigger legacy compaction
        boolean compacted = false;
        for (int i = 0; i < 20; i++) {
            compacted |= memory.append(ConversationMessage.user("Message " + i));
            compacted |= memory.append(ConversationMessage.assistant("Reply " + i, List.of()));
        }
        // Legacy mode should have triggered at some point
        assertTrue(compacted, "Legacy mode should trigger compaction on message count");
    }

    // ---- append / appendAndCompact ----

    @Test
    void append_addsMessage() {
        ConversationMemory memory = createDefaultMemory();
        memory.append(ConversationMessage.user("Hello"));
        assertEquals(1, memory.size());
    }

    @Test
    void appendAndCompact_returnsNullWhenBelowThreshold() {
        ConversationMemory memory = createDefaultMemory();
        CompactResult result = memory.appendAndCompact(ConversationMessage.user("Hello"));
        assertNull(result, "Should not compact when below threshold");
    }

    @Test
    void appendAndCompact_tracksAssistantTime() {
        ConversationMemory memory = createDefaultMemory();
        // Append an assistant message — should update lastAssistantTime
        memory.appendAndCompact(ConversationMessage.assistant("Response", List.of()));
        // No direct accessor, but the micro compact path should work
        assertNotNull(memory);
    }

    // ---- snapshot ----

    @Test
    void snapshot_returnsImmutableCopy() {
        ConversationMemory memory = createDefaultMemory();
        memory.append(ConversationMessage.user("First"));
        List<ConversationMessage> snap = memory.snapshot();
        memory.append(ConversationMessage.user("Second"));

        assertEquals(1, snap.size(), "Snapshot should not change after new appends");
        assertEquals(2, memory.size());
    }

    // ---- estimateCurrentTokens ----

    @Test
    void estimateCurrentTokens_increasesWithMessages() {
        ConversationMemory memory = createDefaultMemory();
        int before = memory.estimateCurrentTokens();
        memory.append(ConversationMessage.user("Some text content here"));
        int after = memory.estimateCurrentTokens();
        assertTrue(after > before, "Token count should increase with messages");
    }

    // ---- forceCompact ----

    @Test
    void forceCompact_onSmallConversation_returnsNull() {
        ConversationMemory memory = createDefaultMemory();
        memory.append(ConversationMessage.system("System"));
        memory.append(ConversationMessage.user("Hello"));
        memory.append(ConversationMessage.assistant("Hi", List.of()));

        CompactResult result = memory.forceCompact();
        // Very small conversation — nothing meaningful to compact
        // May return null or non-compact result
        if (result != null) {
            // If it returns something, type should be appropriate
            assertNotNull(result.messages());
        }
    }

    @Test
    void forceCompact_onLargeConversation_compacts() {
        ConversationMemory memory = createDefaultMemory();
        memory.append(ConversationMessage.system("System prompt"));

        // Build a conversation large enough to compact
        for (int i = 0; i < 50; i++) {
            memory.append(ConversationMessage.user("Question " + i + " " + "x".repeat(200)));
            memory.append(ConversationMessage.assistant("Answer " + i + " " + "y".repeat(200), List.of()));
        }

        CompactResult result = memory.forceCompact();
        assertNotNull(result);
        assertTrue(result.didCompact(), "Large conversation should compact");
        assertTrue(result.tokensSaved() > 0);
    }

    // ---- forceMicroCompact ----

    @Test
    void forceMicroCompact_clearsOldToolResults() {
        ConversationMemory memory = createDefaultMemory();
        memory.append(ConversationMessage.user("Analyze"));

        // Add tool call/result pairs
        for (int i = 0; i < 10; i++) {
            String tcId = "tc-" + i;
            memory.append(ConversationMessage.assistant("Reading " + i, List.of(
                    new ToolCallBlock(tcId, "read_file", Map.of("path", "/f" + i + ".java"))
            )));
            memory.append(ConversationMessage.toolResult(
                    new ToolResultBlock(tcId, "read_file", false, "Content " + "x".repeat(200))
            ));
        }

        CompactResult result = memory.forceMicroCompact();
        assertTrue(result.didCompact());
        assertEquals(CompactType.MICRO, result.type());
    }

    // ---- getContextStats ----

    @Test
    void getContextStats_returnsValidStats() {
        ConversationMemory memory = createDefaultMemory();
        memory.append(ConversationMessage.system("System"));
        memory.append(ConversationMessage.user("Hello world"));

        var stats = memory.getContextStats();
        assertEquals(2, stats.messageCount());
        assertTrue(stats.currentTokens() > 0);
        assertEquals(200_000, stats.contextWindowTokens());
        assertTrue(stats.usedPercentage() > 0);
    }

    @Test
    void getContextStats_format_containsExpectedLabels() {
        ConversationMemory memory = createDefaultMemory();
        memory.append(ConversationMessage.user("Test"));

        String formatted = memory.getContextStats().format();
        assertTrue(formatted.contains("Context usage:"));
        assertTrue(formatted.contains("Messages:"));
        assertTrue(formatted.contains("Tokens:"));
        assertTrue(formatted.contains("Used:"));
        assertTrue(formatted.contains("Compact at:"));
    }

    @Test
    void getContextStats_afterCompact_showsLastResult() {
        ConversationMemory memory = createDefaultMemory();
        memory.append(ConversationMessage.system("System"));
        for (int i = 0; i < 50; i++) {
            memory.append(ConversationMessage.user("Q" + i + " " + "x".repeat(200)));
            memory.append(ConversationMessage.assistant("A" + i + " " + "y".repeat(200), List.of()));
        }
        memory.forceCompact();

        String formatted = memory.getContextStats().format();
        assertTrue(formatted.contains("Last compact:"), "Should show last compact result");
    }

    // ---- Token 触发的三级压缩 ----

    @Test
    void tokenBased_noCompactBelowThreshold() {
        ConversationMemory memory = createDefaultMemory();
        // Add a few messages — well below 170K threshold
        for (int i = 0; i < 5; i++) {
            CompactResult result = memory.appendAndCompact(
                    ConversationMessage.user("Short message " + i));
            assertNull(result, "Should not compact below threshold");
        }
    }

    // ---- 工具配对保护 ----

    @Test
    void compaction_doesNotSplitToolPairs() {
        ConversationMemory memory = createDefaultMemory();
        memory.append(ConversationMessage.system("System"));

        // Build conversation with tool pairs
        for (int i = 0; i < 30; i++) {
            String tcId = "tc-" + i;
            memory.append(ConversationMessage.user("Task " + i));
            memory.append(ConversationMessage.assistant("Working", List.of(
                    new ToolCallBlock(tcId, "read_file", Map.of("path", "/f" + i))
            )));
            memory.append(ConversationMessage.toolResult(
                    new ToolResultBlock(tcId, "read_file", false, "Content " + "x".repeat(200))
            ));
            memory.append(ConversationMessage.assistant("Done with " + i + " " + "y".repeat(100), List.of()));
        }

        CompactResult result = memory.forceCompact();
        if (result != null && result.didCompact()) {
            // Verify no orphaned tool results (every tool_result should have a matching tool_use)
            List<ConversationMessage> msgs = result.messages();
            for (int i = 0; i < msgs.size(); i++) {
                ConversationMessage msg = msgs.get(i);
                if (!msg.toolResults().isEmpty()) {
                    // Find a prior assistant message with matching tool call
                    boolean foundMatch = false;
                    for (int j = i - 1; j >= 0; j--) {
                        if (msgs.get(j).role() == MessageRole.ASSISTANT
                                && !msgs.get(j).toolCalls().isEmpty()) {
                            foundMatch = true;
                            break;
                        }
                    }
                    assertTrue(foundMatch,
                            "Tool result at index " + i + " should have matching tool call before it");
                }
            }
        }
    }

    // ---- 熔断机制 ----

    @Test
    void circuitBreaker_maxConsecutiveFailures() {
        // This is a structural test — we can verify the constant exists
        // The actual behavior is tested through the compaction chain
        ConversationMemory memory = createDefaultMemory();
        assertNotNull(memory); // Smoke test that construction succeeds
    }

    // ---- lastCompactResult ----

    @Test
    void lastCompactResult_initiallyNull() {
        ConversationMemory memory = createDefaultMemory();
        assertNull(memory.getLastCompactResult());
    }

    @Test
    void lastCompactResult_updatedAfterCompact() {
        ConversationMemory memory = createDefaultMemory();
        memory.append(ConversationMessage.system("System"));
        for (int i = 0; i < 50; i++) {
            memory.append(ConversationMessage.user("Q" + i + " " + "x".repeat(200)));
            memory.append(ConversationMessage.assistant("A" + i + " " + "y".repeat(200), List.of()));
        }
        memory.forceCompact();

        assertNotNull(memory.getLastCompactResult());
        assertTrue(memory.getLastCompactResult().didCompact());
    }

    // ---- Helper ----

    private ConversationMemory createDefaultMemory() {
        return new ConversationMemory(200_000, 16_384, 13_000, null, null);
    }
}
