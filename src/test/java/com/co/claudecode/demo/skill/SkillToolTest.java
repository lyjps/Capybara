package com.co.claudecode.demo.skill;

import com.co.claudecode.demo.agent.*;
import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.model.ModelAdapter;
import com.co.claudecode.demo.tool.*;
import com.co.claudecode.demo.tool.impl.ListFilesTool;
import com.co.claudecode.demo.tool.impl.ReadFileTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SkillTool} — inline execution, fork execution, error handling.
 */
class SkillToolTest {

    private static final Path DUMMY_PATH = Path.of("/tmp/test.md");

    private SkillRegistry registry;
    private SubAgentRunner subAgentRunner;
    private AgentRegistry agentRegistry;
    private ToolExecutionContext context;

    /** A ModelAdapter that always returns a simple text reply, no tool calls. */
    private static final ModelAdapter ECHO_MODEL = (conversation, ctx) ->
            ConversationMessage.assistant(
                    "Echo: " + conversation.get(conversation.size() - 1).plainText(),
                    List.of());

    @BeforeEach
    void setUp() {
        context = new ToolExecutionContext(
                Path.of(System.getProperty("java.io.tmpdir")),
                Path.of(System.getProperty("java.io.tmpdir"))
        );
        agentRegistry = AgentRegistry.withBuiltIns();
        AgentTaskRegistry taskRegistry = new AgentTaskRegistry();
        PermissionPolicy policy = new WorkspacePermissionPolicy();

        List<Tool> tools = List.of(new ListFilesTool(), new ReadFileTool());
        subAgentRunner = new SubAgentRunner(ECHO_MODEL, policy, context, taskRegistry, tools, 2);
    }

    private SkillDefinition makeInlineSkill(String name, String prompt) {
        return new SkillDefinition(name, "Inline skill", "", null, null,
                SkillDefinition.ExecutionMode.INLINE, null, prompt, DUMMY_PATH,
                SkillDefinition.Source.USER);
    }

    private SkillDefinition makeForkSkill(String name, String prompt) {
        return new SkillDefinition(name, "Fork skill", "", null, null,
                SkillDefinition.ExecutionMode.FORK, null, prompt, DUMMY_PATH,
                SkillDefinition.Source.USER);
    }

    // ================================================================
    //  Metadata
    // ================================================================

    @Test
    void metadataHasCorrectName() {
        registry = new SkillRegistry(List.of(makeInlineSkill("test", "prompt")));
        SkillTool tool = new SkillTool(registry, subAgentRunner, agentRegistry);

        assertEquals("Skill", tool.metadata().name());
    }

    @Test
    void metadataHasRequiredParams() {
        registry = new SkillRegistry(List.of(makeInlineSkill("test", "prompt")));
        SkillTool tool = new SkillTool(registry, subAgentRunner, agentRegistry);

        List<ToolMetadata.ParamInfo> params = tool.metadata().params();
        assertEquals(2, params.size());

        ToolMetadata.ParamInfo skillParam = params.stream()
                .filter(p -> p.name().equals("skill")).findFirst().orElseThrow();
        assertTrue(skillParam.required());

        ToolMetadata.ParamInfo argsParam = params.stream()
                .filter(p -> p.name().equals("args")).findFirst().orElseThrow();
        assertFalse(argsParam.required());
    }

    @Test
    void metadataDescriptionIncludesSkillNames() {
        registry = new SkillRegistry(List.of(
                makeInlineSkill("alpha", "prompt"),
                makeInlineSkill("beta", "prompt")
        ));
        SkillTool tool = new SkillTool(registry, subAgentRunner, agentRegistry);

        String desc = tool.metadata().description();
        assertTrue(desc.contains("alpha"));
        assertTrue(desc.contains("beta"));
    }

    @Test
    void metadataDescriptionShowsNoneWhenEmpty() {
        registry = new SkillRegistry(List.of());
        SkillTool tool = new SkillTool(registry, subAgentRunner, agentRegistry);

        assertTrue(tool.metadata().description().contains("(none)"));
    }

    // ================================================================
    //  Validation
    // ================================================================

    @Test
    void validateRequiresSkillParam() {
        registry = new SkillRegistry(List.of());
        SkillTool tool = new SkillTool(registry, null, null);

        assertThrows(IllegalArgumentException.class, () ->
                tool.validate(Map.of()));
        assertThrows(IllegalArgumentException.class, () ->
                tool.validate(Map.of("skill", "")));
        assertThrows(IllegalArgumentException.class, () ->
                tool.validate(Map.of("skill", "   ")));
    }

    @Test
    void validateAcceptsValidSkillParam() {
        registry = new SkillRegistry(List.of());
        SkillTool tool = new SkillTool(registry, null, null);

        assertDoesNotThrow(() -> tool.validate(Map.of("skill", "my-skill")));
    }

    // ================================================================
    //  Inline execution
    // ================================================================

    @Test
    void executeInlineReturnsPromptInTags() throws Exception {
        registry = new SkillRegistry(List.of(makeInlineSkill("greet", "Hello, world!")));
        SkillTool tool = new SkillTool(registry, null, null);

        ToolResult result = tool.execute(Map.of("skill", "greet"), context);

        assertFalse(result.error());
        assertTrue(result.content().contains("<skill name=\"greet\">"));
        assertTrue(result.content().contains("Hello, world!"));
        assertTrue(result.content().contains("</skill>"));
    }

    @Test
    void executeInlineSubstitutesArguments() throws Exception {
        registry = new SkillRegistry(List.of(makeInlineSkill("do", "Please $ARGUMENTS now.")));
        SkillTool tool = new SkillTool(registry, null, null);

        ToolResult result = tool.execute(Map.of("skill", "do", "args", "fix the bug"), context);

        assertFalse(result.error());
        assertTrue(result.content().contains("Please fix the bug now."));
    }

    @Test
    void executeInlineNoArgsRemovesPlaceholder() throws Exception {
        registry = new SkillRegistry(List.of(makeInlineSkill("run", "Run $ARGUMENTS task.")));
        SkillTool tool = new SkillTool(registry, null, null);

        ToolResult result = tool.execute(Map.of("skill", "run"), context);

        assertFalse(result.error());
        assertTrue(result.content().contains("Run  task."));
    }

    // ================================================================
    //  Fork execution
    // ================================================================

    @Test
    void executeForkReturnsSubAgentResult() throws Exception {
        registry = new SkillRegistry(List.of(makeForkSkill("forked", "Do something")));
        SkillTool tool = new SkillTool(registry, subAgentRunner, agentRegistry);

        ToolResult result = tool.execute(Map.of("skill", "forked"), context);

        assertFalse(result.error());
        assertTrue(result.content().contains("\"mode\":\"fork\""));
        assertTrue(result.content().contains("\"skill\":\"forked\""));
        assertTrue(result.content().contains("\"status\":\"completed\""));
    }

    @Test
    void executeForkWithoutRunnerReturnsError() throws Exception {
        registry = new SkillRegistry(List.of(makeForkSkill("forked", "Do something")));
        SkillTool tool = new SkillTool(registry, null, null);

        ToolResult result = tool.execute(Map.of("skill", "forked"), context);

        assertTrue(result.error());
        assertTrue(result.content().contains("Fork execution unavailable"));
    }

    // ================================================================
    //  Unknown skill
    // ================================================================

    @Test
    void executeUnknownSkillReturnsError() throws Exception {
        registry = new SkillRegistry(List.of(makeInlineSkill("known", "prompt")));
        SkillTool tool = new SkillTool(registry, null, null);

        ToolResult result = tool.execute(Map.of("skill", "unknown"), context);

        assertTrue(result.error());
        assertTrue(result.content().contains("Unknown skill: unknown"));
        assertTrue(result.content().contains("known"), "Should list available skills");
    }

    @Test
    void executeEmptyRegistryReturnsError() throws Exception {
        registry = new SkillRegistry(List.of());
        SkillTool tool = new SkillTool(registry, null, null);

        ToolResult result = tool.execute(Map.of("skill", "any"), context);

        assertTrue(result.error());
        assertTrue(result.content().contains("(none)"));
    }
}
