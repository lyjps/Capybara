package com.co.claudecode.demo.compact;

import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.message.ContentBlock;
import com.co.claudecode.demo.message.SummaryBlock;
import com.co.claudecode.demo.message.ToolCallBlock;
import com.co.claudecode.demo.message.ToolResultBlock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FullCompactor 单元测试。
 */
class FullCompactorTest {

    // ---- compact(List<ConversationMessage>) ----

    @Test
    void nullMessages_returnsNoContextSummary() {
        ConversationMessage result = FullCompactor.compact(null);
        assertNotNull(result);
        String text = result.renderForModel();
        assertTrue(text.contains("No prior conversation context"));
    }

    @Test
    void emptyMessages_returnsNoContextSummary() {
        ConversationMessage result = FullCompactor.compact(List.of());
        assertNotNull(result);
        String text = result.renderForModel();
        assertTrue(text.contains("No prior conversation context"));
    }

    @Test
    void summaryContainsContinuationHeader() {
        List<ConversationMessage> messages = buildSimpleConversation();
        ConversationMessage result = FullCompactor.compact(messages);
        String text = result.renderForModel();
        assertTrue(text.contains("This session is being continued from a previous conversation"));
    }

    @Test
    void summaryContainsSummaryTags() {
        List<ConversationMessage> messages = buildSimpleConversation();
        ConversationMessage result = FullCompactor.compact(messages);
        String text = result.renderForModel();
        assertTrue(text.contains("<summary>"), "Should have <summary> tag");
        assertTrue(text.contains("</summary>"), "Should have </summary> tag");
    }

    @Test
    void summaryContainsUserIntentSection() {
        List<ConversationMessage> messages = List.of(
                ConversationMessage.user("Please refactor the authentication module"),
                ConversationMessage.assistant("I'll analyze the auth module", List.of())
        );
        ConversationMessage result = FullCompactor.compact(messages);
        String text = result.renderForModel();
        assertTrue(text.contains("## User Intent"), "Should have User Intent section");
        assertTrue(text.contains("refactor the authentication"), "Should capture user request");
    }

    @Test
    void summaryContainsToolCallTrace() {
        List<ConversationMessage> messages = new ArrayList<>();
        messages.add(ConversationMessage.user("Read these files"));
        messages.add(ConversationMessage.assistant("Reading...", List.of(
                new ToolCallBlock("tc1", "read_file", Map.of("path", "/src/Auth.java"))
        )));
        messages.add(ConversationMessage.toolResult(
                new ToolResultBlock("tc1", "read_file", false, "public class Auth {}")
        ));

        ConversationMessage result = FullCompactor.compact(messages);
        String text = result.renderForModel();
        assertTrue(text.contains("## Tool Call Trace"), "Should have Tool Call Trace section");
        assertTrue(text.contains("read_file"), "Should show tool name in trace");
        assertTrue(text.contains("/src/Auth.java"), "Should show tool param in trace");
    }

    @Test
    void summaryContainsKeyDecisions() {
        List<ConversationMessage> messages = List.of(
                ConversationMessage.user("What should we do?"),
                ConversationMessage.assistant(
                        "After analyzing the codebase, I recommend using the Strategy pattern for this refactoring because it allows flexible algorithm switching.",
                        List.of())
        );
        ConversationMessage result = FullCompactor.compact(messages);
        String text = result.renderForModel();
        assertTrue(text.contains("## Key Decisions"), "Should have Key Decisions section");
        assertTrue(text.contains("Strategy pattern"), "Should capture key decisions");
    }

    @Test
    void summaryContainsUnfinishedWork() {
        List<ConversationMessage> messages = new ArrayList<>();
        messages.add(ConversationMessage.user("Fix the bug"));
        // Last assistant message has pending tool calls
        messages.add(ConversationMessage.assistant("Let me investigate", List.of(
                new ToolCallBlock("tc1", "read_file", Map.of("path", "/src/Bug.java"))
        )));

        ConversationMessage result = FullCompactor.compact(messages);
        String text = result.renderForModel();
        assertTrue(text.contains("## Unfinished Work"), "Should have Unfinished Work section");
        assertTrue(text.contains("read_file"), "Should show pending tool call");
    }

    @Test
    void summaryContainsRecentFiles() {
        List<ConversationMessage> messages = new ArrayList<>();
        messages.add(ConversationMessage.user("Analyze files"));
        for (int i = 0; i < 3; i++) {
            messages.add(ConversationMessage.assistant("Reading file " + i, List.of(
                    new ToolCallBlock("tc" + i, "read_file",
                            Map.of("path", "/src/File" + i + ".java"))
            )));
            messages.add(ConversationMessage.toolResult(
                    new ToolResultBlock("tc" + i, "read_file", false, "content " + i)
            ));
        }

        ConversationMessage result = FullCompactor.compact(messages);
        String text = result.renderForModel();
        assertTrue(text.contains("## Recently Accessed Files"), "Should have files section");
        assertTrue(text.contains("/src/File0.java"), "Should list accessed files");
    }

    @Test
    void summaryContainsResumeInstruction() {
        List<ConversationMessage> messages = buildSimpleConversation();
        ConversationMessage result = FullCompactor.compact(messages);
        String text = result.renderForModel();
        assertTrue(text.contains("Continue the conversation from where it left off"),
                "Should contain resume instruction");
    }

    @Test
    void resultIsSummaryBlock() {
        List<ConversationMessage> messages = buildSimpleConversation();
        ConversationMessage result = FullCompactor.compact(messages);
        assertTrue(result.blocks().get(0) instanceof SummaryBlock,
                "Result should contain SummaryBlock");
    }

    // ---- fullCompact(allMessages, protectedPrefix, tailStart) ----

    @Test
    void fullCompact_removesMiddleMessages() {
        List<ConversationMessage> messages = buildLargeConversation(20);
        int protectedPrefix = 1; // system header
        int tailStart = 15; // keep last 5 messages

        CompactResult result = FullCompactor.fullCompact(messages, protectedPrefix, tailStart);
        assertTrue(result.didCompact());
        assertEquals(CompactType.FULL, result.type());
        assertEquals(tailStart - protectedPrefix, result.messagesRemoved());
    }

    @Test
    void fullCompact_preservesSystemHeader() {
        List<ConversationMessage> messages = buildLargeConversation(20);
        CompactResult result = FullCompactor.fullCompact(messages, 1, 15);

        assertEquals("System header", result.messages().get(0).plainText(),
                "System header should be first message");
    }

    @Test
    void fullCompact_preservesTailMessages() {
        List<ConversationMessage> messages = buildLargeConversation(20);
        String lastOriginal = messages.get(messages.size() - 1).plainText();

        CompactResult result = FullCompactor.fullCompact(messages, 1, 15);
        String lastCompacted = result.messages().get(result.messages().size() - 1).plainText();
        assertEquals(lastOriginal, lastCompacted, "Last message should be preserved");
    }

    @Test
    void fullCompact_injectsSummaryMessage() {
        List<ConversationMessage> messages = buildLargeConversation(20);
        CompactResult result = FullCompactor.fullCompact(messages, 1, 15);

        // Second message should be the summary (after system header)
        ConversationMessage summaryMsg = result.messages().get(1);
        assertTrue(summaryMsg.blocks().get(0) instanceof SummaryBlock,
                "Second message should be the injected summary");
    }

    @Test
    void fullCompact_reducesTokenCount() {
        List<ConversationMessage> messages = buildLargeConversation(30);
        CompactResult result = FullCompactor.fullCompact(messages, 1, 20);

        assertTrue(result.tokensSaved() > 0, "Should save tokens, saved: " + result.tokensSaved());
        assertTrue(result.tokensAfterCompact() < result.tokensBeforeCompact());
    }

    @Test
    void fullCompact_summaryDescription() {
        List<ConversationMessage> messages = buildLargeConversation(20);
        CompactResult result = FullCompactor.fullCompact(messages, 1, 15);

        assertTrue(result.summary().contains("Full compact"),
                "Summary should describe the compaction");
        assertTrue(result.summary().contains("removed"),
                "Summary should mention removed messages");
    }

    // ---- MAX_RESTORED_FILES ----

    @Test
    void maxRestoredFiles_isFive() {
        assertEquals(5, FullCompactor.MAX_RESTORED_FILES);
    }

    @Test
    void recentFiles_limitedToMaxRestored() {
        List<ConversationMessage> messages = new ArrayList<>();
        messages.add(ConversationMessage.user("Read many files"));
        // Create more than MAX_RESTORED_FILES file reads
        for (int i = 0; i < 10; i++) {
            messages.add(ConversationMessage.assistant("Reading " + i, List.of(
                    new ToolCallBlock("tc" + i, "read_file",
                            Map.of("path", "/src/File" + i + ".java"))
            )));
            messages.add(ConversationMessage.toolResult(
                    new ToolResultBlock("tc" + i, "read_file", false, "content")
            ));
        }

        ConversationMessage result = FullCompactor.compact(messages);
        String text = result.renderForModel();

        // Extract only the "Recently Accessed Files" section
        int sectionStart = text.indexOf("## Recently Accessed Files");
        assertTrue(sectionStart >= 0, "Should have Recently Accessed Files section");
        // Section ends at next ## or </summary>
        int sectionEnd = text.indexOf("</summary>", sectionStart);
        if (sectionEnd < 0) sectionEnd = text.length();
        String filesSection = text.substring(sectionStart, sectionEnd);

        // Count file references only within this section
        int fileCount = 0;
        for (int i = 0; i < 10; i++) {
            if (filesSection.contains("/src/File" + i + ".java")) {
                fileCount++;
            }
        }
        assertEquals(FullCompactor.MAX_RESTORED_FILES, fileCount,
                "Should limit to exactly MAX_RESTORED_FILES in the files section");

        // Verify it keeps the MOST RECENT files (last 5: File5-File9)
        for (int i = 5; i < 10; i++) {
            assertTrue(filesSection.contains("/src/File" + i + ".java"),
                    "Should keep recent file: /src/File" + i + ".java");
        }
    }

    // ---- Edge cases ----

    @Test
    void toolResultWithError_showsErrorInTrace() {
        List<ConversationMessage> messages = new ArrayList<>();
        messages.add(ConversationMessage.user("Write file"));
        messages.add(ConversationMessage.assistant("Writing...", List.of(
                new ToolCallBlock("tc1", "write_file", Map.of("path", "/out.txt"))
        )));
        messages.add(ConversationMessage.toolResult(
                new ToolResultBlock("tc1", "write_file", true, "Permission denied")
        ));

        ConversationMessage result = FullCompactor.compact(messages);
        String text = result.renderForModel();
        assertTrue(text.contains("ERROR"), "Should indicate error in tool trace");
    }

    @Test
    void userIntentLimitedToFive() {
        List<ConversationMessage> messages = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            messages.add(ConversationMessage.user("User request number " + i));
            messages.add(ConversationMessage.assistant("Response " + i, List.of()));
        }
        ConversationMessage result = FullCompactor.compact(messages);
        String text = result.renderForModel();
        assertTrue(text.contains("earlier requests omitted"),
                "Should indicate earlier requests were omitted");
    }

    @Test
    void noToolCalls_omitsToolTraceSection() {
        List<ConversationMessage> messages = List.of(
                ConversationMessage.user("Just a question"),
                ConversationMessage.assistant("Just an answer with enough chars to be captured as decision", List.of())
        );
        ConversationMessage result = FullCompactor.compact(messages);
        String text = result.renderForModel();
        // Tool Call Trace section should be absent when no tools
        assertFalse(text.contains("## Tool Call Trace"),
                "Should omit Tool Call Trace when no tools used");
    }

    // ---- Helpers ----

    private List<ConversationMessage> buildSimpleConversation() {
        return List.of(
                ConversationMessage.user("Help me understand this code"),
                ConversationMessage.assistant("I'll analyze the codebase for you and provide insights.", List.of())
        );
    }

    private List<ConversationMessage> buildLargeConversation(int turns) {
        List<ConversationMessage> messages = new ArrayList<>();
        messages.add(ConversationMessage.system("System header"));
        for (int i = 0; i < turns; i++) {
            messages.add(ConversationMessage.user("User message " + i + " " + "x".repeat(100)));
            messages.add(ConversationMessage.assistant("Assistant reply " + i + " " + "y".repeat(100), List.of()));
        }
        return messages;
    }
}
