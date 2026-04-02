package com.co.claudecode.demo.compact;

import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.message.ToolCallBlock;
import com.co.claudecode.demo.message.ToolResultBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SessionMemory 单元测试。
 */
class SessionMemoryTest {

    private SessionMemory memory;

    @BeforeEach
    void setUp() {
        memory = new SessionMemory();
    }

    // ---- 基础状态 ----

    @Test
    void newInstance_isEmpty() {
        assertTrue(memory.isEmpty(), "New instance should be considered empty");
    }

    @Test
    void newInstance_hasTemplateContent() {
        String content = memory.getCurrentContent();
        assertNotNull(content);
        assertTrue(content.contains("# Session Title"));
        assertTrue(content.contains("# Current State"));
        assertTrue(content.contains("# Task Specification"));
        assertTrue(content.contains("# Files and Functions"));
        assertTrue(content.contains("# Workflow"));
        assertTrue(content.contains("# Errors & Corrections"));
        assertTrue(content.contains("# Codebase and System Documentation"));
        assertTrue(content.contains("# Learnings"));
        assertTrue(content.contains("# Key Results"));
        assertTrue(content.contains("# Worklog"));
    }

    @Test
    void getTemplate_returnsTemplateWithAllSections() {
        String template = SessionMemory.getTemplate();
        assertNotNull(template);
        assertTrue(template.contains("# Session Title"));
        assertTrue(template.contains("# Worklog"));
    }

    // ---- updateContent / isEmpty ----

    @Test
    void afterUpdateContent_notEmpty() {
        memory.updateContent("# Session Title\nMy custom session\n");
        assertFalse(memory.isEmpty());
    }

    @Test
    void updateContent_setsNewContent() {
        String newContent = "Updated content";
        memory.updateContent(newContent);
        assertEquals(newContent, memory.getCurrentContent());
    }

    @Test
    void nullContent_isConsideredEmpty() {
        memory.updateContent(null);
        assertTrue(memory.isEmpty());
    }

    @Test
    void blankContent_isConsideredEmpty() {
        memory.updateContent("   \n  ");
        assertTrue(memory.isEmpty());
    }

    // ---- extractMemoryFromConversation ----

    @Test
    void emptyMessages_returnsTemplate() {
        String result = memory.extractMemoryFromConversation(List.of());
        assertTrue(result.contains("# Session Title"));
    }

    @Test
    void nullMessages_returnsTemplate() {
        String result = memory.extractMemoryFromConversation(null);
        assertTrue(result.contains("# Session Title"));
    }

    @Test
    void extractSessionTitle_fromFirstUserMessage() {
        List<ConversationMessage> messages = List.of(
                ConversationMessage.user("Implement user authentication for the webapp")
        );
        String result = memory.extractMemoryFromConversation(messages);
        assertTrue(result.contains("Implement user authentication"),
                "Session title should come from first user message");
    }

    @Test
    void extractSessionTitle_truncatesLongMessage() {
        String longMessage = "A".repeat(200);
        List<ConversationMessage> messages = List.of(
                ConversationMessage.user(longMessage)
        );
        String result = memory.extractMemoryFromConversation(messages);
        // Title should be truncated (max 60 chars)
        assertTrue(result.contains("..."), "Long title should be truncated");
    }

    @Test
    void extractFilesAndFunctions_fromReadFileTools() {
        List<ConversationMessage> messages = new ArrayList<>();
        messages.add(ConversationMessage.user("Read these files"));
        messages.add(ConversationMessage.assistant("Reading...", List.of(
                new ToolCallBlock("tc1", "read_file", Map.of("path", "/src/main/Foo.java")),
                new ToolCallBlock("tc2", "read_file", Map.of("path", "/src/main/Bar.java"))
        )));
        messages.add(ConversationMessage.toolResult(
                new ToolResultBlock("tc1", "read_file", false, "content1")));
        messages.add(ConversationMessage.toolResult(
                new ToolResultBlock("tc2", "read_file", false, "content2")));

        String result = memory.extractMemoryFromConversation(messages);
        assertTrue(result.contains("/src/main/Foo.java"), "Should extract file paths");
        assertTrue(result.contains("/src/main/Bar.java"), "Should extract file paths");
    }

    @Test
    void extractErrors_fromErrorToolResults() {
        List<ConversationMessage> messages = new ArrayList<>();
        messages.add(ConversationMessage.user("Try something"));
        messages.add(ConversationMessage.assistant("Trying...", List.of(
                new ToolCallBlock("tc1", "write_file", Map.of("path", "/test.txt"))
        )));
        messages.add(ConversationMessage.toolResult(
                new ToolResultBlock("tc1", "write_file", true, "Permission denied: /test.txt")));

        String result = memory.extractMemoryFromConversation(messages);
        assertTrue(result.contains("Permission denied"), "Should capture error messages");
    }

    @Test
    void extractWorkflow_fromToolCalls() {
        List<ConversationMessage> messages = new ArrayList<>();
        messages.add(ConversationMessage.user("Analyze"));
        messages.add(ConversationMessage.assistant("Step 1", List.of(
                new ToolCallBlock("tc1", "list_files", Map.of("path", "/src"))
        )));
        messages.add(ConversationMessage.toolResult(
                new ToolResultBlock("tc1", "list_files", false, "Foo.java\nBar.java")));
        messages.add(ConversationMessage.assistant("Step 2", List.of(
                new ToolCallBlock("tc2", "read_file", Map.of("path", "/src/Foo.java"))
        )));

        String result = memory.extractMemoryFromConversation(messages);
        assertTrue(result.contains("list_files"), "Should capture tool workflow");
        assertTrue(result.contains("read_file"), "Should capture tool workflow");
    }

    @Test
    void extractKeyResults_fromWriteFileCalls() {
        List<ConversationMessage> messages = new ArrayList<>();
        messages.add(ConversationMessage.user("Generate report"));
        messages.add(ConversationMessage.assistant("Writing...", List.of(
                new ToolCallBlock("tc1", "write_file", Map.of("path", "/output/report.md"))
        )));
        messages.add(ConversationMessage.toolResult(
                new ToolResultBlock("tc1", "write_file", false, "File written successfully")));

        String result = memory.extractMemoryFromConversation(messages);
        assertTrue(result.contains("/output/report.md"), "Should capture written files");
    }

    @Test
    void extractWorklog_recordsTurns() {
        List<ConversationMessage> messages = new ArrayList<>();
        messages.add(ConversationMessage.user("Do tasks"));
        messages.add(ConversationMessage.assistant("Doing task 1", List.of()));
        messages.add(ConversationMessage.user("Next"));
        messages.add(ConversationMessage.assistant("Doing task 2", List.of(
                new ToolCallBlock("tc1", "read_file", Map.of("path", "/f.java"))
        )));

        String result = memory.extractMemoryFromConversation(messages);
        assertTrue(result.contains("Turn 1"), "Should record turn 1");
        assertTrue(result.contains("Turn 2"), "Should record turn 2");
    }

    @Test
    void extractCurrentState_fromRecentAssistantMessages() {
        List<ConversationMessage> messages = new ArrayList<>();
        messages.add(ConversationMessage.user("Start"));
        messages.add(ConversationMessage.assistant("I'm analyzing the codebase", List.of()));
        messages.add(ConversationMessage.user("Continue"));
        messages.add(ConversationMessage.assistant("Now implementing the feature", List.of()));

        String result = memory.extractMemoryFromConversation(messages);
        assertTrue(result.contains("implementing the feature"),
                "Current state should reflect recent assistant messages");
    }

    @Test
    void memoryUpdatedAfterExtract() {
        List<ConversationMessage> messages = List.of(
                ConversationMessage.user("Test extraction"),
                ConversationMessage.assistant("Working on it", List.of())
        );
        memory.extractMemoryFromConversation(messages);
        assertFalse(memory.isEmpty(), "Memory should not be empty after extraction");
        assertTrue(memory.getCurrentContent().contains("Test extraction"));
    }

    // ---- Token limits ----

    @Test
    void maxSectionTokens_constant() {
        assertEquals(2000, SessionMemory.MAX_SECTION_TOKENS);
    }

    @Test
    void maxTotalTokens_constant() {
        assertEquals(12000, SessionMemory.MAX_TOTAL_TOKENS);
    }
}
