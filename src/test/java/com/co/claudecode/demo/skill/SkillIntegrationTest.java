package com.co.claudecode.demo.skill;

import com.co.claudecode.demo.agent.*;
import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.model.ModelAdapter;
import com.co.claudecode.demo.prompt.SystemPromptBuilder;
import com.co.claudecode.demo.tool.*;
import com.co.claudecode.demo.tool.impl.ListFilesTool;
import com.co.claudecode.demo.tool.impl.ReadFileTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for the Skill System:
 * load from disk → register → execute inline/fork → system prompt injection.
 */
class SkillIntegrationTest {

    @TempDir
    Path tempDir;

    private Path skillDir;
    private ToolExecutionContext context;
    private SubAgentRunner subAgentRunner;
    private AgentRegistry agentRegistry;

    private static final ModelAdapter ECHO_MODEL = (conversation, ctx) ->
            ConversationMessage.assistant(
                    "Echo: " + conversation.get(conversation.size() - 1).plainText(),
                    List.of());

    @BeforeEach
    void setUp() throws IOException {
        skillDir = tempDir.resolve(".claude").resolve("skills");
        Files.createDirectories(skillDir);

        context = new ToolExecutionContext(tempDir, tempDir);
        agentRegistry = AgentRegistry.withBuiltIns();
        AgentTaskRegistry taskRegistry = new AgentTaskRegistry();
        PermissionPolicy policy = new WorkspacePermissionPolicy();
        List<Tool> tools = List.of(new ListFilesTool(), new ReadFileTool());
        subAgentRunner = new SubAgentRunner(ECHO_MODEL, policy, context, taskRegistry, tools, 2);
    }

    // ================================================================
    //  Load → Register → Execute (inline)
    // ================================================================

    @Test
    void endToEndInlineExecution() throws Exception {
        // Write a skill file
        Files.writeString(skillDir.resolve("greet.md"), """
                ---
                name: greet
                description: "Greets the user"
                context: inline
                ---
                Hello, $ARGUMENTS! Welcome aboard.
                """);

        // Load
        List<SkillDefinition> skills = SkillLoader.loadFromDirectory(skillDir, SkillDefinition.Source.PROJECT);
        assertEquals(1, skills.size());

        // Register
        SkillRegistry registry = new SkillRegistry(skills);
        assertTrue(registry.hasSkills());
        assertEquals("greet", registry.findByName("greet").name());

        // Execute via SkillTool
        SkillTool tool = new SkillTool(registry, subAgentRunner, agentRegistry);
        ToolResult result = tool.execute(Map.of("skill", "greet", "args", "World"), context);

        assertFalse(result.error());
        assertTrue(result.content().contains("Hello, World! Welcome aboard."));
        assertTrue(result.content().contains("<skill name=\"greet\">"));
    }

    // ================================================================
    //  Load → Register → Execute (fork)
    // ================================================================

    @Test
    void endToEndForkExecution() throws Exception {
        Files.writeString(skillDir.resolve("analyze.md"), """
                ---
                name: analyze
                description: "Analyzes code"
                context: fork
                ---
                Analyze the following code: $ARGUMENTS
                """);

        List<SkillDefinition> skills = SkillLoader.loadFromDirectory(skillDir, SkillDefinition.Source.PROJECT);
        SkillRegistry registry = new SkillRegistry(skills);

        SkillTool tool = new SkillTool(registry, subAgentRunner, agentRegistry);
        ToolResult result = tool.execute(Map.of("skill", "analyze", "args", "main.java"), context);

        assertFalse(result.error());
        assertTrue(result.content().contains("\"mode\":\"fork\""));
        assertTrue(result.content().contains("\"status\":\"completed\""));
    }

    // ================================================================
    //  Load → Register → Execute (subdirectory SKILL.md)
    // ================================================================

    @Test
    void endToEndSubdirectorySkillExecution() throws Exception {
        // 创建子目录模式的 skill
        Path subDir = skillDir.resolve("旅游规划");
        Files.createDirectories(subDir);
        Files.writeString(subDir.resolve("SKILL.md"), """
                ---
                description: "旅行行程规划"
                context: inline
                ---
                帮你规划 $ARGUMENTS 的旅行行程。
                """);

        // Load
        List<SkillDefinition> skills = SkillLoader.loadFromDirectory(skillDir, SkillDefinition.Source.PROJECT);
        assertEquals(1, skills.size());
        assertEquals("旅游规划", skills.get(0).name());

        // Register
        SkillRegistry registry = new SkillRegistry(skills);
        assertTrue(registry.hasSkills());

        // Execute
        SkillTool tool = new SkillTool(registry, subAgentRunner, agentRegistry);
        ToolResult result = tool.execute(Map.of("skill", "旅游规划", "args", "北京三日游"), context);

        assertFalse(result.error());
        assertTrue(result.content().contains("帮你规划 北京三日游 的旅行行程"));
    }

    @Test
    void endToEndMixedFlatAndSubdirSkills() throws Exception {
        // 扁平模式
        Files.writeString(skillDir.resolve("quick.md"), """
                ---
                context: inline
                ---
                Quick flat prompt: $ARGUMENTS
                """);

        // 目录模式
        Path subDir = skillDir.resolve("聚餐管家");
        Files.createDirectories(subDir);
        Files.writeString(subDir.resolve("SKILL.md"), """
                ---
                description: "聚餐规划"
                context: inline
                ---
                帮你规划聚餐: $ARGUMENTS
                """);

        List<SkillDefinition> skills = SkillLoader.loadFromDirectory(skillDir, SkillDefinition.Source.PROJECT);
        SkillRegistry registry = new SkillRegistry(skills);
        assertEquals(2, registry.size());

        SkillTool tool = new SkillTool(registry, subAgentRunner, agentRegistry);

        // 调用扁平 skill
        ToolResult flatResult = tool.execute(Map.of("skill", "quick", "args", "test"), context);
        assertFalse(flatResult.error());
        assertTrue(flatResult.content().contains("Quick flat prompt: test"));

        // 调用目录 skill
        ToolResult dirResult = tool.execute(Map.of("skill", "聚餐管家", "args", "6人聚餐"), context);
        assertFalse(dirResult.error());
        assertTrue(dirResult.content().contains("帮你规划聚餐: 6人聚餐"));
    }

    // ================================================================
    //  Multiple skills with different modes
    // ================================================================

    @Test
    void multipleSkillsLoadAndExecute() throws Exception {
        Files.writeString(skillDir.resolve("quick.md"), """
                ---
                context: inline
                ---
                Quick inline prompt.
                """);

        Files.writeString(skillDir.resolve("heavy.md"), """
                ---
                context: fork
                ---
                Heavy fork prompt.
                """);

        List<SkillDefinition> skills = SkillLoader.loadFromDirectory(skillDir, SkillDefinition.Source.PROJECT);
        SkillRegistry registry = new SkillRegistry(skills);
        assertEquals(2, registry.size());

        SkillTool tool = new SkillTool(registry, subAgentRunner, agentRegistry);

        // Inline skill
        ToolResult inlineResult = tool.execute(Map.of("skill", "quick"), context);
        assertFalse(inlineResult.error());
        assertTrue(inlineResult.content().contains("Quick inline prompt"));

        // Fork skill
        ToolResult forkResult = tool.execute(Map.of("skill", "heavy"), context);
        assertFalse(forkResult.error());
        assertTrue(forkResult.content().contains("\"mode\":\"fork\""));
    }

    // ================================================================
    //  System prompt injection
    // ================================================================

    @Test
    void skillListingInjectedIntoSystemPrompt() throws IOException {
        Files.writeString(skillDir.resolve("my-skill.md"), """
                ---
                description: "Does something cool"
                when_to_use: "When user asks for it"
                ---
                Cool prompt.
                """);

        List<SkillDefinition> skills = SkillLoader.loadFromDirectory(skillDir, SkillDefinition.Source.PROJECT);
        SkillRegistry registry = new SkillRegistry(skills);

        // Test the skillListingSection
        String section = SystemPromptBuilder.skillListingSection(registry);
        assertTrue(section.contains("my-skill"));
        assertTrue(section.contains("Does something cool"));
        assertTrue(section.contains("When user asks for it"));
    }

    @Test
    void skillListingSectionEmptyWhenNoSkills() {
        SkillRegistry registry = new SkillRegistry(List.of());
        assertEquals("", SystemPromptBuilder.skillListingSection(registry));
    }

    @Test
    void skillListingSectionNullRegistryReturnsEmpty() {
        assertEquals("", SystemPromptBuilder.skillListingSection(null));
    }

    // ================================================================
    //  buildMainPrompt backward compatibility
    // ================================================================

    @Test
    void buildMainPromptWithoutSkillRegistryWorks() {
        // Old signature — should still work
        String prompt = SystemPromptBuilder.buildMainPrompt(
                tempDir, "test-model", Set.of("read_file"), null, "Chinese");
        assertNotNull(prompt);
        assertFalse(prompt.isEmpty());
    }

    @Test
    void buildMainPromptWithSkillRegistryIncludesSkills() throws IOException {
        Files.writeString(skillDir.resolve("test-skill.md"), """
                ---
                description: "A test skill"
                ---
                Body.
                """);

        List<SkillDefinition> skills = SkillLoader.loadFromDirectory(skillDir, SkillDefinition.Source.PROJECT);
        SkillRegistry registry = new SkillRegistry(skills);

        String prompt = SystemPromptBuilder.buildMainPrompt(
                tempDir, "test-model", Set.of("Skill"), null, "Chinese", registry);
        assertTrue(prompt.contains("test-skill"));
        assertTrue(prompt.contains("A test skill"));
    }

    @Test
    void buildMainPromptWithNullSkillRegistryExcludesSection() {
        String prompt = SystemPromptBuilder.buildMainPrompt(
                tempDir, "test-model", Set.of("read_file"), null, "Chinese", null);

        // Should not contain "可用 Skills" section header
        assertFalse(prompt.contains("# 可用 Skills"));
    }

    // ================================================================
    //  Empty skill directory gracefully handled
    // ================================================================

    @Test
    void emptySkillDirectoryProducesEmptyRegistry() {
        List<SkillDefinition> skills = SkillLoader.loadFromDirectory(skillDir, SkillDefinition.Source.PROJECT);
        assertTrue(skills.isEmpty());

        SkillRegistry registry = new SkillRegistry(skills);
        assertFalse(registry.hasSkills());
        assertEquals(0, registry.size());
    }

    // ================================================================
    //  SkillTool registered with correct metadata in tools list
    // ================================================================

    @Test
    void skillToolAppearsInToolList() throws IOException {
        Files.writeString(skillDir.resolve("test.md"), "Prompt.");

        List<SkillDefinition> skills = SkillLoader.loadFromDirectory(skillDir, SkillDefinition.Source.PROJECT);
        SkillRegistry registry = new SkillRegistry(skills);
        SkillTool skillTool = new SkillTool(registry, null, null);

        // Verify it can be registered alongside other tools
        List<Tool> allTools = List.of(new ListFilesTool(), new ReadFileTool(), skillTool);
        ToolRegistry toolRegistry = new ToolRegistry(allTools);

        Tool found = toolRegistry.require("Skill");
        assertNotNull(found);
        assertEquals("Skill", found.metadata().name());
    }
}
