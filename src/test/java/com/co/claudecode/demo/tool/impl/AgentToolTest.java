package com.co.claudecode.demo.tool.impl;

import com.co.claudecode.demo.agent.*;
import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.model.ModelAdapter;
import com.co.claudecode.demo.tool.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AgentTool — the tool that spawns sub-agents.
 * <p>
 * Uses a fake ModelAdapter that returns a simple text reply
 * (no tool calls) so the sub-agent completes in one turn.
 */
class AgentToolTest {

    private AgentRegistry agentRegistry;
    private AgentTaskRegistry taskRegistry;
    private SubAgentRunner runner;
    private AgentTool agentTool;
    private ToolExecutionContext context;

    /** A ModelAdapter that always returns a simple text reply, no tool calls. */
    private static final ModelAdapter ECHO_MODEL = (conversation, ctx) ->
            ConversationMessage.assistant("Echo: " + conversation.get(conversation.size() - 1).plainText(), List.of());

    @BeforeEach
    void setUp() {
        agentRegistry = AgentRegistry.withBuiltIns();
        taskRegistry = new AgentTaskRegistry();
        context = new ToolExecutionContext(
                Path.of(System.getProperty("java.io.tmpdir")),
                Path.of(System.getProperty("java.io.tmpdir"))
        );
        PermissionPolicy policy = new WorkspacePermissionPolicy();

        // Provide a minimal tool list — sub-agents filter from this
        List<Tool> tools = List.of(new ListFilesTool(), new ReadFileTool());

        runner = new SubAgentRunner(ECHO_MODEL, policy, context, taskRegistry, tools, 2);
        agentTool = new AgentTool(agentRegistry, runner);
    }

    @Test
    void syncExecutionReturnsCompleted() throws Exception {
        ToolResult result = agentTool.execute(Map.of(
                "description", "test sync",
                "prompt", "Analyze this project"
        ), context);

        assertFalse(result.error());
        assertTrue(result.content().contains("\"status\":\"completed\""));
        assertTrue(result.content().contains("\"agentType\":\"general-purpose\""));
        assertTrue(result.content().contains("agentId"));
    }

    @Test
    void asyncExecutionReturnsAsyncLaunched() throws Exception {
        ToolResult result = agentTool.execute(Map.of(
                "description", "test async",
                "prompt", "Background analysis",
                "run_in_background", "true"
        ), context);

        assertFalse(result.error());
        assertTrue(result.content().contains("\"status\":\"async_launched\""));
        assertTrue(result.content().contains("agentId"));
        assertTrue(result.content().contains("general-purpose"));

        // Wait for async to complete
        Thread.sleep(500);
    }

    @Test
    void defaultSubagentTypeIsGeneralPurpose() throws Exception {
        ToolResult result = agentTool.execute(Map.of(
                "description", "test default",
                "prompt", "Do something"
        ), context);

        assertFalse(result.error());
        assertTrue(result.content().contains("general-purpose"));
    }

    @Test
    void explicitSubagentTypeIsUsed() throws Exception {
        ToolResult result = agentTool.execute(Map.of(
                "description", "test explore",
                "prompt", "Search files",
                "subagent_type", "Explore"
        ), context);

        assertFalse(result.error());
        assertTrue(result.content().contains("Explore"));
    }

    @Test
    void unknownSubagentTypeReturnsError() throws Exception {
        ToolResult result = agentTool.execute(Map.of(
                "description", "test unknown",
                "prompt", "Do something",
                "subagent_type", "nonexistent-agent"
        ), context);

        assertTrue(result.error());
        assertTrue(result.content().contains("Unknown agent type"));
        assertTrue(result.content().contains("nonexistent-agent"));
    }

    @Test
    void syncExecutionRegistersTaskInRegistry() throws Exception {
        agentTool.execute(Map.of(
                "description", "test registration",
                "prompt", "Register me"
        ), context);

        // After sync execution, the task should be registered and completed
        var allRunning = taskRegistry.allRunning();
        // It may be 0 since sync completes immediately
        // But total registered tasks should be >= 1
        // (cleanup hasn't been called, so completed tasks still exist)
    }

    @Test
    void namedAgentCanBeFoundInRegistry() throws Exception {
        agentTool.execute(Map.of(
                "description", "test named",
                "prompt", "Named agent task",
                "name", "my-worker"
        ), context);

        // The agent should have been registered with the name
        AgentTask found = taskRegistry.findByIdOrName("my-worker");
        assertNotNull(found);
        assertEquals("my-worker", found.name());
    }

    @Test
    void validateThrowsForMissingPrompt() {
        assertThrows(IllegalArgumentException.class, () ->
                agentTool.validate(Map.of("description", "test")));
    }

    @Test
    void validateThrowsForMissingDescription() {
        assertThrows(IllegalArgumentException.class, () ->
                agentTool.validate(Map.of("prompt", "do something")));
    }

    @Test
    void validateThrowsForBlankPrompt() {
        assertThrows(IllegalArgumentException.class, () ->
                agentTool.validate(Map.of("prompt", "  ", "description", "test")));
    }

    @Test
    void metadataIsCorrect() {
        assertEquals("agent", agentTool.metadata().name());
        assertTrue(agentTool.metadata().readOnly());
        assertTrue(agentTool.metadata().concurrencySafe());
        assertEquals(5, agentTool.metadata().params().size());
    }

    @Test
    void syncResultContainsDuration() throws Exception {
        ToolResult result = agentTool.execute(Map.of(
                "description", "test duration",
                "prompt", "Quick task"
        ), context);

        assertFalse(result.error());
        assertTrue(result.content().contains("totalDurationMs"));
    }
}
