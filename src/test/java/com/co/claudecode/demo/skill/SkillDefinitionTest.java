package com.co.claudecode.demo.skill;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SkillDefinition} — record construction, defaults, enums, and prompt resolution.
 */
class SkillDefinitionTest {

    private static final Path DUMMY_PATH = Path.of("/tmp/test.md");

    @Test
    void constructionWithAllFields() {
        var skill = new SkillDefinition(
                "my-skill",
                "A test skill",
                "When user asks for help",
                List.of("read_file", "write_file"),
                "sonnet",
                SkillDefinition.ExecutionMode.FORK,
                List.of(new SkillDefinition.SkillArgument("arg1", "First arg", true)),
                "Do $ARGUMENTS",
                DUMMY_PATH,
                SkillDefinition.Source.PROJECT
        );

        assertEquals("my-skill", skill.name());
        assertEquals("A test skill", skill.description());
        assertEquals("When user asks for help", skill.whenToUse());
        assertEquals(List.of("read_file", "write_file"), skill.allowedTools());
        assertEquals("sonnet", skill.model());
        assertEquals(SkillDefinition.ExecutionMode.FORK, skill.context());
        assertEquals(1, skill.arguments().size());
        assertEquals("arg1", skill.arguments().get(0).name());
        assertTrue(skill.arguments().get(0).required());
        assertEquals("Do $ARGUMENTS", skill.promptTemplate());
        assertEquals(DUMMY_PATH, skill.sourceFile());
        assertEquals(SkillDefinition.Source.PROJECT, skill.source());
    }

    @Test
    void nullDefaultsAreApplied() {
        var skill = new SkillDefinition(
                "test", null, null, null, null, null, null, null, DUMMY_PATH,
                SkillDefinition.Source.USER
        );

        assertEquals("", skill.description());
        assertEquals("", skill.whenToUse());
        assertNull(skill.allowedTools());
        assertNull(skill.model());
        assertEquals(SkillDefinition.ExecutionMode.INLINE, skill.context());
        assertEquals(List.of(), skill.arguments());
        assertEquals("", skill.promptTemplate());
    }

    @Test
    void blankNameThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new SkillDefinition("", "desc", "", null, null, null, null, "", DUMMY_PATH,
                        SkillDefinition.Source.USER));
    }

    @Test
    void nullNameThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new SkillDefinition(null, "desc", "", null, null, null, null, "", DUMMY_PATH,
                        SkillDefinition.Source.USER));
    }

    @Test
    void allowedToolsAreDefensivelyCopied() {
        var mutable = new java.util.ArrayList<>(List.of("a", "b"));
        var skill = new SkillDefinition("test", "", "", mutable, null, null, null, "", DUMMY_PATH,
                SkillDefinition.Source.USER);
        mutable.add("c");
        assertEquals(2, skill.allowedTools().size(), "Should be a defensive copy");
    }

    @Test
    void argumentsAreDefensivelyCopied() {
        var mutable = new java.util.ArrayList<>(List.of(
                new SkillDefinition.SkillArgument("x", "desc", false)));
        var skill = new SkillDefinition("test", "", "", null, null, null, mutable, "", DUMMY_PATH,
                SkillDefinition.Source.USER);
        mutable.clear();
        assertEquals(1, skill.arguments().size(), "Should be a defensive copy");
    }

    // ================================================================
    //  resolvePrompt
    // ================================================================

    @Test
    void resolvePromptSubstitutesArguments() {
        var skill = new SkillDefinition("test", "", "", null, null, null, null,
                "Please $ARGUMENTS now.",
                DUMMY_PATH, SkillDefinition.Source.USER);

        assertEquals("Please fix the bug now.", skill.resolvePrompt("fix the bug"));
    }

    @Test
    void resolvePromptRemovesPlaceholderWhenNoArgs() {
        var skill = new SkillDefinition("test", "", "", null, null, null, null,
                "Run $ARGUMENTS task.",
                DUMMY_PATH, SkillDefinition.Source.USER);

        assertEquals("Run  task.", skill.resolvePrompt(null));
        assertEquals("Run  task.", skill.resolvePrompt(""));
        assertEquals("Run  task.", skill.resolvePrompt("   "));
    }

    @Test
    void resolvePromptStripsResult() {
        var skill = new SkillDefinition("test", "", "", null, null, null, null,
                "  hello  ",
                DUMMY_PATH, SkillDefinition.Source.USER);

        assertEquals("hello", skill.resolvePrompt(null));
    }

    // ================================================================
    //  ExecutionMode enum
    // ================================================================

    @Test
    void executionModeValues() {
        assertEquals(2, SkillDefinition.ExecutionMode.values().length);
        assertNotNull(SkillDefinition.ExecutionMode.valueOf("INLINE"));
        assertNotNull(SkillDefinition.ExecutionMode.valueOf("FORK"));
    }

    // ================================================================
    //  Source enum
    // ================================================================

    @Test
    void sourceValues() {
        assertEquals(2, SkillDefinition.Source.values().length);
        assertNotNull(SkillDefinition.Source.valueOf("USER"));
        assertNotNull(SkillDefinition.Source.valueOf("PROJECT"));
    }

    // ================================================================
    //  SkillArgument
    // ================================================================

    @Test
    void skillArgumentConstruction() {
        var arg = new SkillDefinition.SkillArgument("name", "description", true);
        assertEquals("name", arg.name());
        assertEquals("description", arg.description());
        assertTrue(arg.required());
    }

    @Test
    void skillArgumentNullDescriptionDefault() {
        var arg = new SkillDefinition.SkillArgument("name", null, false);
        assertEquals("", arg.description());
    }

    @Test
    void skillArgumentBlankNameThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new SkillDefinition.SkillArgument("", "desc", false));
        assertThrows(IllegalArgumentException.class, () ->
                new SkillDefinition.SkillArgument(null, "desc", false));
    }
}
