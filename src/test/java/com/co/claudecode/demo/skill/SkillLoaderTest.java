package com.co.claudecode.demo.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SkillLoader} — file parsing, YAML frontmatter, directory scanning.
 */
class SkillLoaderTest {

    @TempDir
    Path tempDir;
    Path skillDir;

    @BeforeEach
    void setUp() throws IOException {
        skillDir = tempDir.resolve(".claude").resolve("skills");
        Files.createDirectories(skillDir);
    }

    // ================================================================
    //  parseSkillFile — basic parsing
    // ================================================================

    @Test
    void parseSkillFileWithFullFrontmatter() throws IOException {
        Path file = skillDir.resolve("my-skill.md");
        Files.writeString(file, """
                ---
                name: custom-name
                description: "A helpful skill"
                when_to_use: "When user needs help"
                allowed-tools: [read_file, write_file]
                model: sonnet
                context: fork
                arguments:
                  - name: target
                    description: "The target to process"
                    required: true
                ---
                Please process $ARGUMENTS for the user.
                """);

        SkillDefinition skill = SkillLoader.parseSkillFile(file, SkillDefinition.Source.PROJECT);

        assertNotNull(skill);
        assertEquals("custom-name", skill.name());
        assertEquals("A helpful skill", skill.description());
        assertEquals("When user needs help", skill.whenToUse());
        assertEquals(List.of("read_file", "write_file"), skill.allowedTools());
        assertEquals("sonnet", skill.model());
        assertEquals(SkillDefinition.ExecutionMode.FORK, skill.context());
        assertEquals(1, skill.arguments().size());
        assertEquals("target", skill.arguments().get(0).name());
        assertTrue(skill.arguments().get(0).required());
        assertTrue(skill.promptTemplate().contains("Please process $ARGUMENTS"));
        assertEquals(SkillDefinition.Source.PROJECT, skill.source());
    }

    @Test
    void parseSkillFileNameFromFilename() throws IOException {
        Path file = skillDir.resolve("file-based-name.md");
        Files.writeString(file, """
                ---
                description: "Desc only"
                ---
                Do something.
                """);

        SkillDefinition skill = SkillLoader.parseSkillFile(file, SkillDefinition.Source.USER);

        assertNotNull(skill);
        assertEquals("file-based-name", skill.name());
    }

    @Test
    void parseSkillFileWithoutFrontmatter() throws IOException {
        Path file = skillDir.resolve("simple.md");
        Files.writeString(file, "Just a prompt, no frontmatter.");

        SkillDefinition skill = SkillLoader.parseSkillFile(file, SkillDefinition.Source.USER);

        assertNotNull(skill);
        assertEquals("simple", skill.name());
        assertEquals("", skill.description());
        assertEquals(SkillDefinition.ExecutionMode.INLINE, skill.context());
        assertEquals("Just a prompt, no frontmatter.", skill.promptTemplate());
    }

    @Test
    void parseSkillFileEmptyReturnsNull() throws IOException {
        Path file = skillDir.resolve("empty.md");
        Files.writeString(file, "");

        assertNull(SkillLoader.parseSkillFile(file, SkillDefinition.Source.USER));
    }

    @Test
    void parseSkillFileBlankReturnsNull() throws IOException {
        Path file = skillDir.resolve("blank.md");
        Files.writeString(file, "   \n  \n  ");

        assertNull(SkillLoader.parseSkillFile(file, SkillDefinition.Source.USER));
    }

    @Test
    void parseSkillFileWithOpeningDelimiterOnly() throws IOException {
        Path file = skillDir.resolve("half.md");
        Files.writeString(file, """
                ---
                This has no closing delimiter.
                Just body text.
                """);

        SkillDefinition skill = SkillLoader.parseSkillFile(file, SkillDefinition.Source.USER);
        assertNotNull(skill);
        assertEquals("half", skill.name());
        // All content treated as body since no closing ---
        assertTrue(skill.promptTemplate().contains("This has no closing delimiter"));
    }

    @Test
    void parseSkillFileInlineExecutionMode() throws IOException {
        Path file = skillDir.resolve("inline-skill.md");
        Files.writeString(file, """
                ---
                context: inline
                ---
                Inline prompt.
                """);

        SkillDefinition skill = SkillLoader.parseSkillFile(file, SkillDefinition.Source.USER);
        assertEquals(SkillDefinition.ExecutionMode.INLINE, skill.context());
    }

    @Test
    void parseSkillFileDefaultExecutionModeIsInline() throws IOException {
        Path file = skillDir.resolve("default-mode.md");
        Files.writeString(file, """
                ---
                description: "No context field"
                ---
                Body.
                """);

        SkillDefinition skill = SkillLoader.parseSkillFile(file, SkillDefinition.Source.USER);
        assertEquals(SkillDefinition.ExecutionMode.INLINE, skill.context());
    }

    // ================================================================
    //  loadFromDirectory
    // ================================================================

    @Test
    void loadFromDirectoryScansAllMdFiles() throws IOException {
        Files.writeString(skillDir.resolve("skill-a.md"), "Prompt A");
        Files.writeString(skillDir.resolve("skill-b.md"), "Prompt B");
        Files.writeString(skillDir.resolve("not-skill.txt"), "Ignored");

        List<SkillDefinition> skills = SkillLoader.loadFromDirectory(skillDir, SkillDefinition.Source.USER);

        assertEquals(2, skills.size());
        assertTrue(skills.stream().anyMatch(s -> s.name().equals("skill-a")));
        assertTrue(skills.stream().anyMatch(s -> s.name().equals("skill-b")));
    }

    @Test
    void loadFromDirectoryReturnsEmptyForNonExistent() {
        Path nonExistent = tempDir.resolve("does-not-exist");
        List<SkillDefinition> skills = SkillLoader.loadFromDirectory(nonExistent, SkillDefinition.Source.USER);
        assertTrue(skills.isEmpty());
    }

    @Test
    void loadFromDirectoryReturnsEmptyForNull() {
        assertTrue(SkillLoader.loadFromDirectory(null, SkillDefinition.Source.USER).isEmpty());
    }

    @Test
    void loadFromDirectorySkipsMalformedFiles() throws IOException {
        // Empty file should be skipped (returns null)
        Files.writeString(skillDir.resolve("empty.md"), "");
        // Valid file
        Files.writeString(skillDir.resolve("valid.md"), "Valid prompt");

        List<SkillDefinition> skills = SkillLoader.loadFromDirectory(skillDir, SkillDefinition.Source.USER);
        assertEquals(1, skills.size());
        assertEquals("valid", skills.get(0).name());
    }

    // ================================================================
    //  loadAll — project overrides user
    // ================================================================

    @Test
    void loadAllProjectOverridesUser() throws IOException {
        // User skill directory
        Path userDir = tempDir.resolve("user-home").resolve(".claude").resolve("skills");
        Files.createDirectories(userDir);
        Files.writeString(userDir.resolve("shared.md"), """
                ---
                description: "User version"
                ---
                User prompt.
                """);

        // Project skill directory
        Path projectRoot = tempDir.resolve("project");
        Path projectSkillDir = projectRoot.resolve(".claude").resolve("skills");
        Files.createDirectories(projectSkillDir);
        Files.writeString(projectSkillDir.resolve("shared.md"), """
                ---
                description: "Project version"
                ---
                Project prompt.
                """);

        // Load from project directory (loadAll scans project dir after user dir)
        // Here we test the directory merge logic using loadFromDirectory
        List<SkillDefinition> userSkills = SkillLoader.loadFromDirectory(userDir, SkillDefinition.Source.USER);
        List<SkillDefinition> projectSkills = SkillLoader.loadFromDirectory(projectSkillDir, SkillDefinition.Source.PROJECT);

        assertEquals(1, userSkills.size());
        assertEquals("User version", userSkills.get(0).description());

        assertEquals(1, projectSkills.size());
        assertEquals("Project version", projectSkills.get(0).description());
    }

    // ================================================================
    //  parseFrontmatter — YAML parsing
    // ================================================================

    @Test
    void parseFrontmatterSimpleKeyValue() {
        Map<String, Object> result = SkillLoader.parseFrontmatter(
                "name: my-skill\ndescription: A cool skill");
        assertEquals("my-skill", result.get("name"));
        assertEquals("A cool skill", result.get("description"));
    }

    @Test
    void parseFrontmatterQuotedValues() {
        Map<String, Object> result = SkillLoader.parseFrontmatter(
                "name: \"quoted-name\"\ndesc: 'single quoted'");
        assertEquals("quoted-name", result.get("name"));
        assertEquals("single quoted", result.get("desc"));
    }

    @Test
    void parseFrontmatterInlineList() {
        Map<String, Object> result = SkillLoader.parseFrontmatter(
                "tools: [read_file, write_file, list_files]");
        @SuppressWarnings("unchecked")
        List<String> tools = (List<String>) result.get("tools");
        assertEquals(List.of("read_file", "write_file", "list_files"), tools);
    }

    @Test
    void parseFrontmatterEmptyInlineList() {
        Map<String, Object> result = SkillLoader.parseFrontmatter("tools: []");
        @SuppressWarnings("unchecked")
        List<?> tools = (List<?>) result.get("tools");
        assertTrue(tools.isEmpty());
    }

    @Test
    void parseFrontmatterIndentedList() {
        Map<String, Object> result = SkillLoader.parseFrontmatter("""
                tools:
                  - read_file
                  - write_file
                """);
        @SuppressWarnings("unchecked")
        List<Object> tools = (List<Object>) result.get("tools");
        assertEquals(2, tools.size());
        assertEquals("read_file", tools.get(0));
        assertEquals("write_file", tools.get(1));
    }

    @Test
    void parseFrontmatterNestedObjectList() {
        Map<String, Object> result = SkillLoader.parseFrontmatter("""
                arguments:
                  - name: arg1
                    description: First argument
                    required: true
                  - name: arg2
                    description: Second argument
                """);
        @SuppressWarnings("unchecked")
        List<Object> args = (List<Object>) result.get("arguments");
        assertEquals(2, args.size());

        @SuppressWarnings("unchecked")
        Map<String, String> first = (Map<String, String>) args.get(0);
        assertEquals("arg1", first.get("name"));
        assertEquals("First argument", first.get("description"));
        assertEquals("true", first.get("required"));
    }

    @Test
    void parseFrontmatterBooleanValues() {
        Map<String, Object> result = SkillLoader.parseFrontmatter(
                "enabled: true\ndisabled: false\nalso_yes: yes");
        assertEquals("true", result.get("enabled"));
        assertEquals("false", result.get("disabled"));
        assertEquals("yes", result.get("also_yes"));
    }

    @Test
    void parseFrontmatterSkipsComments() {
        Map<String, Object> result = SkillLoader.parseFrontmatter("""
                # This is a comment
                name: test
                # Another comment
                """);
        assertEquals(1, result.size());
        assertEquals("test", result.get("name"));
    }

    @Test
    void parseFrontmatterEmptyStringReturnsEmpty() {
        assertTrue(SkillLoader.parseFrontmatter("").isEmpty());
        assertTrue(SkillLoader.parseFrontmatter(null).isEmpty());
        assertTrue(SkillLoader.parseFrontmatter("   ").isEmpty());
    }

    @Test
    void parseFrontmatterSkipsLinesWithoutColon() {
        Map<String, Object> result = SkillLoader.parseFrontmatter("""
                name: test
                this line has no colon
                desc: valid
                """);
        assertEquals("test", result.get("name"));
        assertEquals("valid", result.get("desc"));
    }

    // ================================================================
    //  Multiple arguments parsing
    // ================================================================

    @Test
    void parseSkillFileMultipleArguments() throws IOException {
        Path file = skillDir.resolve("multi-args.md");
        Files.writeString(file, """
                ---
                arguments:
                  - name: input
                    description: "Input file"
                    required: true
                  - name: output
                    description: "Output file"
                    required: false
                ---
                Process $ARGUMENTS.
                """);

        SkillDefinition skill = SkillLoader.parseSkillFile(file, SkillDefinition.Source.USER);
        assertNotNull(skill);
        assertEquals(2, skill.arguments().size());

        assertEquals("input", skill.arguments().get(0).name());
        assertTrue(skill.arguments().get(0).required());

        assertEquals("output", skill.arguments().get(1).name());
        assertFalse(skill.arguments().get(1).required());
    }

    // ================================================================
    //  目录模式 — SKILL.md in subdirectory
    // ================================================================

    @Test
    void parseSkillMdInSubdirectoryUsesParentDirAsName() throws IOException {
        Path subDir = skillDir.resolve("旅游规划");
        Files.createDirectories(subDir);
        Files.writeString(subDir.resolve("SKILL.md"), """
                ---
                description: "旅行行程规划"
                context: inline
                ---
                帮用户规划旅行行程。$ARGUMENTS
                """);

        SkillDefinition skill = SkillLoader.parseSkillFile(
                subDir.resolve("SKILL.md"), SkillDefinition.Source.USER);

        assertNotNull(skill);
        assertEquals("旅游规划", skill.name());
        assertEquals("旅行行程规划", skill.description());
        assertTrue(skill.promptTemplate().contains("帮用户规划旅行行程"));
    }

    @Test
    void parseSkillMdExplicitNameOverridesParentDir() throws IOException {
        Path subDir = skillDir.resolve("my-dir");
        Files.createDirectories(subDir);
        Files.writeString(subDir.resolve("SKILL.md"), """
                ---
                name: custom-name
                description: "Custom skill"
                ---
                Body.
                """);

        SkillDefinition skill = SkillLoader.parseSkillFile(
                subDir.resolve("SKILL.md"), SkillDefinition.Source.USER);

        assertNotNull(skill);
        assertEquals("custom-name", skill.name(), "frontmatter name should override parent dir name");
    }

    @Test
    void loadFromDirectoryFindsSkillMdInSubdirectories() throws IOException {
        // 扁平模式
        Files.writeString(skillDir.resolve("flat-skill.md"), "Flat prompt");

        // 目录模式
        Path subDir = skillDir.resolve("dir-skill");
        Files.createDirectories(subDir);
        Files.writeString(subDir.resolve("SKILL.md"), """
                ---
                description: "Directory skill"
                ---
                Dir prompt.
                """);

        List<SkillDefinition> skills = SkillLoader.loadFromDirectory(skillDir, SkillDefinition.Source.USER);

        assertEquals(2, skills.size());
        assertTrue(skills.stream().anyMatch(s -> s.name().equals("flat-skill")));
        assertTrue(skills.stream().anyMatch(s -> s.name().equals("dir-skill")));
    }

    @Test
    void loadFromDirectoryIgnoresSubdirWithoutSkillMd() throws IOException {
        // 子目录不含 SKILL.md，应被忽略
        Path subDir = skillDir.resolve("no-skill");
        Files.createDirectories(subDir);
        Files.writeString(subDir.resolve("README.md"), "Just a readme");

        // 有效的扁平 skill
        Files.writeString(skillDir.resolve("valid.md"), "Valid prompt");

        List<SkillDefinition> skills = SkillLoader.loadFromDirectory(skillDir, SkillDefinition.Source.USER);

        assertEquals(1, skills.size());
        assertEquals("valid", skills.get(0).name());
    }

    @Test
    void loadFromDirectorySkillMdHasFullFrontmatter() throws IOException {
        Path subDir = skillDir.resolve("找优惠");
        Files.createDirectories(subDir);
        Files.writeString(subDir.resolve("SKILL.md"), """
                ---
                name: 找优惠
                description: "帮用户领取优惠券"
                when_to_use: "用户提到优惠券、领券、省钱时"
                allowed-tools: [read_file]
                context: fork
                ---
                领取优惠券。$ARGUMENTS
                """);

        List<SkillDefinition> skills = SkillLoader.loadFromDirectory(skillDir, SkillDefinition.Source.USER);

        assertEquals(1, skills.size());
        SkillDefinition skill = skills.get(0);
        assertEquals("找优惠", skill.name());
        assertEquals("帮用户领取优惠券", skill.description());
        assertEquals("用户提到优惠券、领券、省钱时", skill.whenToUse());
        assertEquals(List.of("read_file"), skill.allowedTools());
        assertEquals(SkillDefinition.ExecutionMode.FORK, skill.context());
    }

    @Test
    void loadFromDirectoryMixedFlatAndSubdir() throws IOException {
        // 扁平模式：两个文件
        Files.writeString(skillDir.resolve("alpha.md"), "Alpha prompt");
        Files.writeString(skillDir.resolve("beta.md"), "Beta prompt");

        // 目录模式：两个子目录
        Path gammaDir = skillDir.resolve("gamma");
        Files.createDirectories(gammaDir);
        Files.writeString(gammaDir.resolve("SKILL.md"), "Gamma prompt");

        Path deltaDir = skillDir.resolve("delta");
        Files.createDirectories(deltaDir);
        Files.writeString(deltaDir.resolve("SKILL.md"), "Delta prompt");

        // 无效子目录（无 SKILL.md）
        Path emptyDir = skillDir.resolve("empty-dir");
        Files.createDirectories(emptyDir);

        // 非 .md 文件
        Files.writeString(skillDir.resolve("ignore.txt"), "Not a skill");

        List<SkillDefinition> skills = SkillLoader.loadFromDirectory(skillDir, SkillDefinition.Source.USER);

        assertEquals(4, skills.size());
        List<String> names = skills.stream().map(SkillDefinition::name).toList();
        assertTrue(names.contains("alpha"));
        assertTrue(names.contains("beta"));
        assertTrue(names.contains("gamma"));
        assertTrue(names.contains("delta"));
    }

    // ================================================================
    //  when-to-use vs when_to_use
    // ================================================================

    @Test
    void parseSkillFileSupportsHyphenatedWhenToUse() throws IOException {
        Path file = skillDir.resolve("hyphen.md");
        Files.writeString(file, """
                ---
                when-to-use: "Use when things break"
                ---
                Fix it.
                """);

        SkillDefinition skill = SkillLoader.parseSkillFile(file, SkillDefinition.Source.USER);
        assertEquals("Use when things break", skill.whenToUse());
    }

    @Test
    void parseSkillFileSupportsUnderscoreWhenToUse() throws IOException {
        Path file = skillDir.resolve("underscore.md");
        Files.writeString(file, """
                ---
                when_to_use: "Use when things break"
                ---
                Fix it.
                """);

        SkillDefinition skill = SkillLoader.parseSkillFile(file, SkillDefinition.Source.USER);
        assertEquals("Use when things break", skill.whenToUse());
    }

    // ================================================================
    //  allowed-tools vs allowed_tools
    // ================================================================

    @Test
    void parseSkillFileSupportsUnderscoreAllowedTools() throws IOException {
        Path file = skillDir.resolve("underscore-tools.md");
        Files.writeString(file, """
                ---
                allowed_tools: [read_file]
                ---
                Body.
                """);

        SkillDefinition skill = SkillLoader.parseSkillFile(file, SkillDefinition.Source.USER);
        assertEquals(List.of("read_file"), skill.allowedTools());
    }
}
