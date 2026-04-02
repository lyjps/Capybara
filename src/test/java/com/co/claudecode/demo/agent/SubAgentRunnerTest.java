package com.co.claudecode.demo.agent;

import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.model.ModelAdapter;
import com.co.claudecode.demo.tool.*;
import com.co.claudecode.demo.tool.impl.ListFilesTool;
import com.co.claudecode.demo.tool.impl.ReadFileTool;
import com.co.claudecode.demo.tool.impl.WriteFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SubAgentRunnerTest {

    private AgentTaskRegistry taskRegistry;
    private ToolExecutionContext context;
    private SubAgentRunner runner;

    /** A model that immediately returns text with no tool calls → agent completes in 1 turn. */
    private static final ModelAdapter ECHO_MODEL = (conversation, ctx) ->
            ConversationMessage.assistant(
                    "Result: " + conversation.get(conversation.size() - 1).plainText(),
                    List.of());

    /** A model that always fails → tests error handling. */
    private static final ModelAdapter FAILING_MODEL = (conversation, ctx) -> {
        throw new RuntimeException("Simulated model failure");
    };

    @BeforeEach
    void setUp() {
        taskRegistry = new AgentTaskRegistry();
        context = new ToolExecutionContext(
                Path.of(System.getProperty("java.io.tmpdir")),
                Path.of(System.getProperty("java.io.tmpdir"))
        );
        List<Tool> tools = List.of(new ListFilesTool(), new ReadFileTool(), new WriteFileTool());
        runner = new SubAgentRunner(ECHO_MODEL, new WorkspacePermissionPolicy(),
                context, taskRegistry, tools, 2);
    }

    @AfterEach
    void tearDown() {
        runner.close();
    }

    // ---- Sync execution ----

    @Test
    void runSyncReturnsCompletedResult() {
        AgentDefinition gp = BuiltInAgents.GENERAL_PURPOSE;
        AgentResult result = runner.runSync(gp, "Hello world", null, s -> {});

        assertEquals(AgentResult.Status.COMPLETED, result.status());
        assertNotNull(result.agentId());
        assertEquals("general-purpose", result.agentType());
        assertTrue(result.content().contains("Hello world"));
        assertTrue(result.totalDurationMs() >= 0);
    }

    @Test
    void runSyncRegistersAgentTask() {
        AgentDefinition gp = BuiltInAgents.GENERAL_PURPOSE;
        AgentResult result = runner.runSync(gp, "Test task", null, s -> {});

        AgentTask task = taskRegistry.findByIdOrName(result.agentId());
        assertNotNull(task);
        assertEquals(AgentTask.Status.COMPLETED, task.status());
        assertNotNull(task.result());
    }

    @Test
    void runSyncWithNameRegistersName() {
        AgentDefinition gp = BuiltInAgents.GENERAL_PURPOSE;
        runner.runSync(gp, "Named task", "my-agent", s -> {});

        AgentTask task = taskRegistry.findByIdOrName("my-agent");
        assertNotNull(task);
        assertEquals("my-agent", task.name());
    }

    @Test
    void runSyncWithExploreAgent() {
        AgentDefinition explore = BuiltInAgents.EXPLORE;
        AgentResult result = runner.runSync(explore, "Search files", null, s -> {});

        assertEquals(AgentResult.Status.COMPLETED, result.status());
        assertEquals("Explore", result.agentType());
    }

    @Test
    void runSyncWithFailingModelReturnsFailed() {
        List<Tool> tools = List.of(new ListFilesTool());
        try (SubAgentRunner failRunner = new SubAgentRunner(FAILING_MODEL,
                new WorkspacePermissionPolicy(), context, taskRegistry, tools, 1)) {
            AgentDefinition gp = BuiltInAgents.GENERAL_PURPOSE;
            AgentResult result = failRunner.runSync(gp, "This will fail", null, s -> {});

            assertEquals(AgentResult.Status.FAILED, result.status());
            assertTrue(result.content().contains("Simulated model failure"));
        }
    }

    @Test
    void runSyncFailureRegistersFailedTask() {
        List<Tool> tools = List.of(new ListFilesTool());
        try (SubAgentRunner failRunner = new SubAgentRunner(FAILING_MODEL,
                new WorkspacePermissionPolicy(), context, taskRegistry, tools, 1)) {
            AgentDefinition gp = BuiltInAgents.GENERAL_PURPOSE;
            AgentResult result = failRunner.runSync(gp, "Fail", null, s -> {});

            AgentTask task = taskRegistry.findByIdOrName(result.agentId());
            assertNotNull(task);
            assertEquals(AgentTask.Status.FAILED, task.status());
        }
    }

    @Test
    void runSyncCollectsEvents() {
        List<String> events = new ArrayList<>();
        AgentDefinition gp = BuiltInAgents.GENERAL_PURPOSE;
        runner.runSync(gp, "With events", null, events::add);

        // Should have at least TURN and ASSIST events
        assertFalse(events.isEmpty());
        assertTrue(events.stream().anyMatch(e -> e.contains("TURN")));
        assertTrue(events.stream().anyMatch(e -> e.contains("ASSIST")));
    }

    // ---- Async execution ----

    @Test
    void runAsyncReturnsHandleImmediately() {
        AgentDefinition gp = BuiltInAgents.GENERAL_PURPOSE;
        AsyncAgentHandle handle = runner.runAsync(gp, "Async task", null, s -> {});

        assertNotNull(handle.agentId());
        assertEquals("general-purpose", handle.agentType());
        assertNotNull(handle.future());
    }

    @Test
    void runAsyncCompletesInBackground() throws Exception {
        AgentDefinition gp = BuiltInAgents.GENERAL_PURPOSE;
        AsyncAgentHandle handle = runner.runAsync(gp, "Background work", null, s -> {});

        AgentResult result = handle.future().get(5, TimeUnit.SECONDS);
        assertEquals(AgentResult.Status.COMPLETED, result.status());
        assertTrue(result.content().contains("Background work"));
    }

    @Test
    void runAsyncRegistersTaskBeforeCompletion() {
        AgentDefinition gp = BuiltInAgents.GENERAL_PURPOSE;
        AsyncAgentHandle handle = runner.runAsync(gp, "Async reg", null, s -> {});

        // Task should be registered immediately (before execution completes)
        AgentTask task = taskRegistry.findByIdOrName(handle.agentId());
        assertNotNull(task);
    }

    @Test
    void runAsyncWithNameUsesName() throws Exception {
        AgentDefinition gp = BuiltInAgents.GENERAL_PURPOSE;
        AsyncAgentHandle handle = runner.runAsync(gp, "Named async", "async-worker", s -> {});

        handle.future().get(5, TimeUnit.SECONDS);
        AgentTask task = taskRegistry.findByIdOrName("async-worker");
        assertNotNull(task);
        assertEquals("async-worker", task.name());
    }

    @Test
    void runAsyncFailingModelReturnsFailed() throws Exception {
        List<Tool> tools = List.of(new ListFilesTool());
        try (SubAgentRunner failRunner = new SubAgentRunner(FAILING_MODEL,
                new WorkspacePermissionPolicy(), context, taskRegistry, tools, 1)) {
            AgentDefinition gp = BuiltInAgents.GENERAL_PURPOSE;
            AsyncAgentHandle handle = failRunner.runAsync(gp, "Fail async", null, s -> {});

            AgentResult result = handle.future().get(5, TimeUnit.SECONDS);
            assertEquals(AgentResult.Status.FAILED, result.status());
        }
    }

    // ---- Tool filtering ----

    @Test
    void exploreAgentFiltersOutWriteTool() {
        // Explore agent has allowedTools=[list_files, read_file] and disallowedTools=[write_file]
        AgentDefinition explore = BuiltInAgents.EXPLORE;

        // WriteFileTool should be filtered out
        assertFalse(explore.isToolAllowed("write_file"));
        assertTrue(explore.isToolAllowed("list_files"));
        assertTrue(explore.isToolAllowed("read_file"));
    }

    @Test
    void generalPurposeAgentAllowsAllTools() {
        AgentDefinition gp = BuiltInAgents.GENERAL_PURPOSE;

        assertTrue(gp.isToolAllowed("list_files"));
        assertTrue(gp.isToolAllowed("read_file"));
        assertTrue(gp.isToolAllowed("write_file"));
        assertTrue(gp.isToolAllowed("any_unknown_tool"));
    }

    // ---- Multiple concurrent agents ----

    @Test
    void multipleConcurrentAgents() throws Exception {
        AgentDefinition gp = BuiltInAgents.GENERAL_PURPOSE;

        AsyncAgentHandle h1 = runner.runAsync(gp, "Task 1", "agent-a", s -> {});
        AsyncAgentHandle h2 = runner.runAsync(gp, "Task 2", "agent-b", s -> {});

        AgentResult r1 = h1.future().get(5, TimeUnit.SECONDS);
        AgentResult r2 = h2.future().get(5, TimeUnit.SECONDS);

        assertEquals(AgentResult.Status.COMPLETED, r1.status());
        assertEquals(AgentResult.Status.COMPLETED, r2.status());
        assertNotEquals(r1.agentId(), r2.agentId());

        // Both should be in the task registry
        assertNotNull(taskRegistry.findByIdOrName("agent-a"));
        assertNotNull(taskRegistry.findByIdOrName("agent-b"));
    }

    @Test
    void agentIdsAreUnique() {
        AgentDefinition gp = BuiltInAgents.GENERAL_PURPOSE;
        AgentResult r1 = runner.runSync(gp, "A", null, s -> {});
        AgentResult r2 = runner.runSync(gp, "B", null, s -> {});
        assertNotEquals(r1.agentId(), r2.agentId());
    }
}
