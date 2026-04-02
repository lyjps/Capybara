package com.co.claudecode.demo.compact;

import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.message.ContentBlock;
import com.co.claudecode.demo.message.TextBlock;
import com.co.claudecode.demo.message.ToolCallBlock;
import com.co.claudecode.demo.message.ToolResultBlock;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MicroCompactor 单元测试。
 */
class MicroCompactorTest {

    // ---- tryMicroCompact: 触发条件 ----

    @Test
    void disabledConfig_doesNotCompact() {
        List<ConversationMessage> messages = buildConversationWithToolResults(10);
        Instant longAgo = Instant.now().minus(120, ChronoUnit.MINUTES);

        CompactResult result = MicroCompactor.tryMicroCompact(messages, longAgo, MicroCompactConfig.DEFAULT);
        assertFalse(result.didCompact(), "Disabled config should not trigger compaction");
    }

    @Test
    void nullLastAssistantTime_doesNotCompact() {
        List<ConversationMessage> messages = buildConversationWithToolResults(10);

        CompactResult result = MicroCompactor.tryMicroCompact(messages, null, MicroCompactConfig.ENABLED);
        assertFalse(result.didCompact());
    }

    @Test
    void recentAssistantTime_doesNotCompact() {
        List<ConversationMessage> messages = buildConversationWithToolResults(10);
        // Only 5 minutes ago — below 60-minute threshold
        Instant recent = Instant.now().minus(5, ChronoUnit.MINUTES);

        CompactResult result = MicroCompactor.tryMicroCompact(messages, recent, MicroCompactConfig.ENABLED);
        assertFalse(result.didCompact(), "Recent gap should not trigger compaction");
    }

    @Test
    void oldAssistantTime_triggersCompaction() {
        List<ConversationMessage> messages = buildConversationWithToolResults(10);
        // 120 minutes ago — above 60-minute threshold
        Instant longAgo = Instant.now().minus(120, ChronoUnit.MINUTES);

        CompactResult result = MicroCompactor.tryMicroCompact(messages, longAgo, MicroCompactConfig.ENABLED);
        assertTrue(result.didCompact(), "Old gap should trigger compaction");
    }

    @Test
    void tooFewResults_doesNotCompact() {
        // Only 3 clearable results, keepRecent=5 → nothing to clear
        List<ConversationMessage> messages = buildConversationWithToolResults(3);
        Instant longAgo = Instant.now().minus(120, ChronoUnit.MINUTES);

        CompactResult result = MicroCompactor.tryMicroCompact(messages, longAgo, MicroCompactConfig.ENABLED);
        assertFalse(result.didCompact(), "Too few results should not compact");
    }

    // ---- forceCleanup: 清理行为 ----

    @Test
    void forceCleanup_keepsRecentResults() {
        List<ConversationMessage> messages = buildConversationWithToolResults(10);

        CompactResult result = MicroCompactor.forceCleanup(messages, 5);
        assertTrue(result.didCompact());
        assertEquals(CompactType.MICRO, result.type());

        // Count remaining clearable tool results that still have original content
        long originalContentCount = countOriginalToolResults(result.messages());
        // Should keep 5 recent ones with original content
        assertEquals(5, originalContentCount, "Should keep 5 recent tool results");
    }

    @Test
    void forceCleanup_replacesOldResultsWithPlaceholder() {
        List<ConversationMessage> messages = buildConversationWithToolResults(8);

        CompactResult result = MicroCompactor.forceCleanup(messages, 3);
        assertTrue(result.didCompact());

        // 5 old results should be replaced with placeholder
        long clearedCount = countClearedToolResults(result.messages());
        assertEquals(5, clearedCount, "Should clear 5 old tool results");
    }

    @Test
    void forceCleanup_preservesNonClearableTools() {
        List<ConversationMessage> messages = new ArrayList<>();
        // Add a non-clearable tool result (e.g., "agent" tool)
        messages.add(ConversationMessage.user("start"));
        messages.add(ConversationMessage.assistant("calling agent", List.of(
                new ToolCallBlock("tc-agent", "agent", Map.of("prompt", "do stuff"))
        )));
        messages.add(ConversationMessage.toolResult(
                new ToolResultBlock("tc-agent", "agent", false, "Agent result content that should be preserved")
        ));
        // Add clearable results
        for (int i = 0; i < 8; i++) {
            messages.add(ConversationMessage.assistant("reading", List.of(
                    new ToolCallBlock("tc-" + i, "read_file", Map.of("path", "/file" + i + ".java"))
            )));
            messages.add(ConversationMessage.toolResult(
                    new ToolResultBlock("tc-" + i, "read_file", false, "Content of file " + i)
            ));
        }

        CompactResult result = MicroCompactor.forceCleanup(messages, 3);
        assertTrue(result.didCompact());

        // Agent tool result should be preserved
        boolean agentResultPreserved = result.messages().stream()
                .flatMap(m -> m.blocks().stream())
                .filter(ToolResultBlock.class::isInstance)
                .map(ToolResultBlock.class::cast)
                .anyMatch(trb -> "agent".equals(trb.toolName())
                        && trb.content().contains("Agent result content"));
        assertTrue(agentResultPreserved, "Non-clearable tool results should be preserved");
    }

    @Test
    void forceCleanup_savesTokens() {
        List<ConversationMessage> messages = buildConversationWithToolResults(10);

        CompactResult result = MicroCompactor.forceCleanup(messages, 5);
        assertTrue(result.didCompact());
        assertTrue(result.tokensSaved() > 0, "Should report tokens saved");
        assertTrue(result.tokensAfterCompact() < result.tokensBeforeCompact(),
                "Tokens after should be less than before");
    }

    @Test
    void forceCleanup_messagesRemovedIsZero() {
        // Micro compact replaces content, not removes messages
        List<ConversationMessage> messages = buildConversationWithToolResults(10);

        CompactResult result = MicroCompactor.forceCleanup(messages, 5);
        assertEquals(0, result.messagesRemoved(), "Micro compact should not remove messages");
        assertEquals(messages.size(), result.messages().size(),
                "Message count should be unchanged");
    }

    @Test
    void customGapThreshold_respected() {
        List<ConversationMessage> messages = buildConversationWithToolResults(10);
        // Custom config: 10-minute threshold
        MicroCompactConfig config = new MicroCompactConfig(true, 10, 5);
        // 15 minutes ago → above 10-minute threshold
        Instant ago = Instant.now().minus(15, ChronoUnit.MINUTES);

        CompactResult result = MicroCompactor.tryMicroCompact(messages, ago, config);
        assertTrue(result.didCompact(), "Custom gap threshold should trigger compaction");
    }

    @Test
    void customKeepRecent_respected() {
        List<ConversationMessage> messages = buildConversationWithToolResults(10);
        // Custom config: keep only 2 recent
        MicroCompactConfig config = new MicroCompactConfig(true, 60, 2);
        Instant longAgo = Instant.now().minus(120, ChronoUnit.MINUTES);

        CompactResult result = MicroCompactor.tryMicroCompact(messages, longAgo, config);
        assertTrue(result.didCompact());

        long originalCount = countOriginalToolResults(result.messages());
        assertEquals(2, originalCount, "Should keep only 2 recent results");
    }

    // ---- Helpers ----

    /**
     * 构建包含 N 个 read_file 工具结果的对话。
     */
    private List<ConversationMessage> buildConversationWithToolResults(int count) {
        List<ConversationMessage> messages = new ArrayList<>();
        messages.add(ConversationMessage.user("Analyze this project"));
        for (int i = 0; i < count; i++) {
            String toolCallId = "tc-" + i;
            messages.add(ConversationMessage.assistant("Reading file " + i, List.of(
                    new ToolCallBlock(toolCallId, "read_file",
                            Map.of("path", "/src/file" + i + ".java"))
            )));
            messages.add(ConversationMessage.toolResult(
                    new ToolResultBlock(toolCallId, "read_file", false,
                            "package com.example;\npublic class File" + i
                                    + " {\n  // long content here...\n  private int value = " + i + ";\n}")
            ));
        }
        return messages;
    }

    private long countOriginalToolResults(List<ConversationMessage> messages) {
        return messages.stream()
                .flatMap(m -> m.blocks().stream())
                .filter(ToolResultBlock.class::isInstance)
                .map(ToolResultBlock.class::cast)
                .filter(trb -> !trb.content().startsWith("[Old tool result content cleared"))
                .count();
    }

    private long countClearedToolResults(List<ConversationMessage> messages) {
        return messages.stream()
                .flatMap(m -> m.blocks().stream())
                .filter(ToolResultBlock.class::isInstance)
                .map(ToolResultBlock.class::cast)
                .filter(trb -> trb.content().startsWith("[Old tool result content cleared"))
                .count();
    }
}
