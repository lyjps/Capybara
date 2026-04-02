package com.co.claudecode.demo.compact;

import com.co.claudecode.demo.agent.ConversationMemory;
import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.message.MessageRole;
import com.co.claudecode.demo.message.SummaryBlock;
import com.co.claudecode.demo.message.ToolCallBlock;
import com.co.claudecode.demo.message.ToolResultBlock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 三级压缩系统端到端集成测试。
 * <p>
 * 验证 ConversationMemory → 三级压缩 → 摘要质量 的完整流程。
 */
class CompactIntegrationTest {

    // ---- 端到端：构造对话 → 自动/手动压缩 → 验证结果 ----

    @Test
    void fullPipeline_microThenFullCompact() {
        SessionMemory sessionMemory = new SessionMemory();
        ConversationMemory memory = new ConversationMemory(
                200_000, 16_384, 13_000, sessionMemory, MicroCompactConfig.ENABLED);

        // Add system prompt
        memory.append(ConversationMessage.system("You are a helpful assistant."));

        // Simulate a conversation with many tool calls
        for (int i = 0; i < 30; i++) {
            String tcId = "tc-" + i;
            memory.append(ConversationMessage.user("Read file " + i));
            memory.append(ConversationMessage.assistant("Reading file " + i, List.of(
                    new ToolCallBlock(tcId, "read_file",
                            Map.of("path", "/src/File" + i + ".java"))
            )));
            memory.append(ConversationMessage.toolResult(
                    new ToolResultBlock(tcId, "read_file", false,
                            "package com.example;\n// File " + i + " content\n" + "x".repeat(200))
            ));
            memory.append(ConversationMessage.assistant(
                    "File " + i + " contains package declaration and some code " + "y".repeat(100),
                    List.of()));
        }

        int sizeBefore = memory.size();
        int tokensBefore = memory.estimateCurrentTokens();

        // Force compaction
        CompactResult result = memory.forceCompact();
        assertNotNull(result, "Should be able to compact");
        assertTrue(result.didCompact());

        int sizeAfter = memory.size();
        int tokensAfter = memory.estimateCurrentTokens();

        assertTrue(sizeAfter < sizeBefore, "Size should decrease after compaction");
        assertTrue(tokensAfter < tokensBefore, "Tokens should decrease after compaction");
    }

    @Test
    void fullPipeline_sessionMemoryCompact() {
        SessionMemory sessionMemory = new SessionMemory();
        // Pre-populate session memory
        sessionMemory.updateContent(
                "# Session Title\nProject analysis session\n\n"
                        + "# Current State\nReviewing codebase\n\n"
                        + "# Task Specification\nAnalyze the Java project architecture\n\n"
                        + "# Files and Functions\n- /src/Main.java\n- /src/Config.java\n"
        );

        ConversationMemory memory = new ConversationMemory(
                200_000, 16_384, 13_000, sessionMemory, MicroCompactConfig.DEFAULT);

        memory.append(ConversationMessage.system("System prompt"));
        // Build large conversation
        for (int i = 0; i < 40; i++) {
            memory.append(ConversationMessage.user("Question " + i + " " + "q".repeat(200)));
            memory.append(ConversationMessage.assistant("Answer " + i + " " + "a".repeat(200), List.of()));
        }

        CompactResult result = memory.forceCompact();
        assertNotNull(result);
        assertTrue(result.didCompact());

        // Verify system header preserved
        assertEquals(MessageRole.SYSTEM, memory.snapshot().get(0).role());
    }

    @Test
    void fullPipeline_preservesRecentMessages() {
        ConversationMemory memory = new ConversationMemory(
                200_000, 16_384, 13_000, null, null);

        memory.append(ConversationMessage.system("System"));
        for (int i = 0; i < 40; i++) {
            memory.append(ConversationMessage.user("Msg " + i + " " + "x".repeat(200)));
            memory.append(ConversationMessage.assistant("Reply " + i + " " + "y".repeat(200), List.of()));
        }

        String lastUserMsg = "Msg 39";
        CompactResult result = memory.forceCompact();
        assertNotNull(result);

        // Last user message should still be in the snapshot
        List<ConversationMessage> snapshot = memory.snapshot();
        boolean lastFound = snapshot.stream()
                .anyMatch(m -> m.plainText().contains(lastUserMsg));
        assertTrue(lastFound, "Most recent messages should be preserved after compaction");
    }

    @Test
    void fullPipeline_summaryHasStructuredFormat() {
        ConversationMemory memory = new ConversationMemory(
                200_000, 16_384, 13_000, null, null);

        memory.append(ConversationMessage.system("System"));
        memory.append(ConversationMessage.user("Build a REST API for user management"));
        for (int i = 0; i < 30; i++) {
            String tcId = "tc-" + i;
            memory.append(ConversationMessage.assistant("Step " + i, List.of(
                    new ToolCallBlock(tcId, "read_file",
                            Map.of("path", "/src/api/UserController" + i + ".java"))
            )));
            memory.append(ConversationMessage.toolResult(
                    new ToolResultBlock(tcId, "read_file", false, "Controller code " + "x".repeat(200))
            ));
            memory.append(ConversationMessage.assistant(
                    "Analyzed controller " + i + ", implementing endpoint pattern " + "y".repeat(80),
                    List.of()));
        }

        CompactResult result = memory.forceCompact();
        assertNotNull(result);

        // Find summary block in compacted messages
        boolean foundSummary = memory.snapshot().stream()
                .flatMap(m -> m.blocks().stream())
                .anyMatch(b -> b instanceof SummaryBlock);
        assertTrue(foundSummary, "Compacted messages should contain a summary block");

        // Verify summary mentions user intent
        String allText = memory.snapshot().stream()
                .map(ConversationMessage::renderForModel)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(allText.contains("REST API") || allText.contains("user management"),
                "Summary should capture original user intent");
    }

    @Test
    void fullPipeline_microCompactSavesTokens() {
        ConversationMemory memory = new ConversationMemory(
                200_000, 16_384, 13_000, null, MicroCompactConfig.ENABLED);

        memory.append(ConversationMessage.user("Start"));
        // Add many tool results
        for (int i = 0; i < 20; i++) {
            String tcId = "tc-" + i;
            memory.append(ConversationMessage.assistant("Reading " + i, List.of(
                    new ToolCallBlock(tcId, "read_file", Map.of("path", "/f" + i + ".java"))
            )));
            memory.append(ConversationMessage.toolResult(
                    new ToolResultBlock(tcId, "read_file", false,
                            "Very long file content " + "content ".repeat(100))
            ));
        }

        int tokensBefore = memory.estimateCurrentTokens();
        CompactResult result = memory.forceMicroCompact();

        assertTrue(result.didCompact(), "Micro compact should trigger with 20 tool results");
        assertTrue(memory.estimateCurrentTokens() < tokensBefore,
                "Tokens should decrease after micro compact");
    }

    @Test
    void fullPipeline_contextStats_reflectsState() {
        ConversationMemory memory = new ConversationMemory(
                200_000, 16_384, 13_000, new SessionMemory(), MicroCompactConfig.DEFAULT);

        memory.append(ConversationMessage.system("System prompt"));
        memory.append(ConversationMessage.user("Hello world"));
        memory.append(ConversationMessage.assistant("Hi there", List.of()));

        var stats = memory.getContextStats();
        assertEquals(3, stats.messageCount());
        assertEquals(200_000, stats.contextWindowTokens());
        assertTrue(stats.usedPercentage() < 1.0, "Small conversation should use < 1% context");
    }

    @Test
    void fullPipeline_toolPairsPreservedThroughCompaction() {
        ConversationMemory memory = new ConversationMemory(
                200_000, 16_384, 13_000, null, null);

        memory.append(ConversationMessage.system("System"));

        // Create interleaved tool call patterns
        for (int i = 0; i < 25; i++) {
            String tcId = "tc-" + i;
            memory.append(ConversationMessage.user("Task " + i));
            memory.append(ConversationMessage.assistant("Working on " + i, List.of(
                    new ToolCallBlock(tcId, "read_file", Map.of("path", "/f" + i + ".java"))
            )));
            memory.append(ConversationMessage.toolResult(
                    new ToolResultBlock(tcId, "read_file", false,
                            "File " + i + " content " + "x".repeat(150))
            ));
            memory.append(ConversationMessage.assistant(
                    "Completed task " + i + " " + "y".repeat(100), List.of()));
        }

        memory.forceCompact();

        // Verify all tool results in snapshot have a preceding assistant with tool calls
        List<ConversationMessage> snapshot = memory.snapshot();
        for (int i = 0; i < snapshot.size(); i++) {
            ConversationMessage msg = snapshot.get(i);
            if (!msg.toolResults().isEmpty()) {
                boolean foundAssistant = false;
                for (int j = i - 1; j >= 0; j--) {
                    if (snapshot.get(j).role() == MessageRole.ASSISTANT
                            && !snapshot.get(j).toolCalls().isEmpty()) {
                        foundAssistant = true;
                        break;
                    }
                }
                assertTrue(foundAssistant,
                        "Tool result at index " + i + " must have preceding assistant with tool call");
            }
        }
    }

    @Test
    void fullPipeline_multipleCompactions_progressive() {
        ConversationMemory memory = new ConversationMemory(
                200_000, 16_384, 13_000, new SessionMemory(), MicroCompactConfig.DEFAULT);

        memory.append(ConversationMessage.system("System"));

        // First batch of messages
        for (int i = 0; i < 30; i++) {
            memory.append(ConversationMessage.user("Batch1-Q" + i + " " + "x".repeat(200)));
            memory.append(ConversationMessage.assistant("Batch1-A" + i + " " + "y".repeat(200), List.of()));
        }
        memory.forceCompact();
        int sizeAfterFirst = memory.size();

        // Second batch of messages
        for (int i = 0; i < 30; i++) {
            memory.append(ConversationMessage.user("Batch2-Q" + i + " " + "x".repeat(200)));
            memory.append(ConversationMessage.assistant("Batch2-A" + i + " " + "y".repeat(200), List.of()));
        }
        memory.forceCompact();
        int sizeAfterSecond = memory.size();

        // Both compactions should have reduced size
        assertTrue(sizeAfterFirst < 61 + 1, // 61 messages + system header, should be less
                "First compaction should reduce size");
        assertTrue(sizeAfterSecond < sizeAfterFirst + 60,
                "Second compaction should reduce accumulated messages");
    }
}
