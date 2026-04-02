package com.co.claudecode.demo.task;

import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolResult;
import com.co.claudecode.demo.tool.impl.TaskCreateTool;
import com.co.claudecode.demo.tool.impl.TaskGetTool;
import com.co.claudecode.demo.tool.impl.TaskListTool;
import com.co.claudecode.demo.tool.impl.TaskUpdateTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TaskToolsTest {

    private TaskStore store;
    private ToolExecutionContext context;
    private TaskCreateTool createTool;
    private TaskGetTool getTool;
    private TaskListTool listTool;
    private TaskUpdateTool updateTool;

    @BeforeEach
    void setUp() {
        store = new TaskStore();
        context = new ToolExecutionContext(
                Path.of(System.getProperty("java.io.tmpdir")),
                Path.of(System.getProperty("java.io.tmpdir"))
        );
        createTool = new TaskCreateTool(store);
        getTool = new TaskGetTool(store);
        listTool = new TaskListTool(store);
        updateTool = new TaskUpdateTool(store);
    }

    // ---- TaskCreateTool ----

    @Test
    void createToolCreatesTask() {
        ToolResult result = createTool.execute(
                Map.of("subject", "Fix bug", "description", "In module X"), context);
        assertFalse(result.error());
        assertTrue(result.content().contains("#1"));
        assertTrue(result.content().contains("Fix bug"));
        assertEquals(1, store.size());
    }

    @Test
    void createToolWithMissingSubjectReturnsError() {
        ToolResult result = createTool.execute(Map.of("description", "desc"), context);
        assertTrue(result.error());
        assertTrue(result.content().contains("subject"));
    }

    @Test
    void createToolWithBlankSubjectReturnsError() {
        ToolResult result = createTool.execute(
                Map.of("subject", "  ", "description", "desc"), context);
        assertTrue(result.error());
    }

    @Test
    void createToolDefaultsEmptyDescription() {
        ToolResult result = createTool.execute(Map.of("subject", "Task"), context);
        assertFalse(result.error());
        Task task = store.getTask("1");
        assertEquals("", task.description());
    }

    // ---- TaskGetTool ----

    @Test
    void getToolReturnsTaskDetail() {
        store.createTask("Test Task", "Test Description");
        ToolResult result = getTool.execute(Map.of("taskId", "1"), context);
        assertFalse(result.error());
        assertTrue(result.content().contains("Test Task"));
        assertTrue(result.content().contains("Test Description"));
        assertTrue(result.content().contains("pending"));
    }

    @Test
    void getToolReturnsErrorForMissingId() {
        ToolResult result = getTool.execute(Map.of(), context);
        assertTrue(result.error());
        assertTrue(result.content().contains("taskId"));
    }

    @Test
    void getToolReturnsErrorForUnknownTask() {
        ToolResult result = getTool.execute(Map.of("taskId", "999"), context);
        assertTrue(result.error());
        assertTrue(result.content().contains("not found"));
    }

    // ---- TaskListTool ----

    @Test
    void listToolReturnsAllTasks() {
        store.createTask("Task A", "desc");
        store.createTask("Task B", "desc");
        ToolResult result = listTool.execute(Map.of(), context);
        assertFalse(result.error());
        assertTrue(result.content().contains("Task A"));
        assertTrue(result.content().contains("Task B"));
    }

    @Test
    void listToolReturnsMessageWhenEmpty() {
        ToolResult result = listTool.execute(Map.of(), context);
        assertFalse(result.error());
        assertTrue(result.content().contains("No tasks"));
    }

    @Test
    void listToolShowsStatus() {
        store.createTask("Task A", "desc");
        store.updateTask("1", TaskStatus.IN_PROGRESS, null, null, null, null, null);
        ToolResult result = listTool.execute(Map.of(), context);
        assertTrue(result.content().contains("in_progress"));
    }

    // ---- TaskUpdateTool ----

    @Test
    void updateToolChangesStatus() {
        store.createTask("Task", "desc");
        ToolResult result = updateTool.execute(
                Map.of("taskId", "1", "status", "in_progress"), context);
        assertFalse(result.error());
        assertEquals(TaskStatus.IN_PROGRESS, store.getTask("1").status());
    }

    @Test
    void updateToolChangesSubject() {
        store.createTask("Old Title", "desc");
        ToolResult result = updateTool.execute(
                Map.of("taskId", "1", "subject", "New Title"), context);
        assertFalse(result.error());
        assertEquals("New Title", store.getTask("1").subject());
    }

    @Test
    void updateToolChangesOwner() {
        store.createTask("Task", "desc");
        ToolResult result = updateTool.execute(
                Map.of("taskId", "1", "owner", "agent-42"), context);
        assertFalse(result.error());
        assertEquals("agent-42", store.getTask("1").owner());
    }

    @Test
    void updateToolDeletesTask() {
        store.createTask("Task", "desc");
        assertEquals(1, store.size());
        ToolResult result = updateTool.execute(
                Map.of("taskId", "1", "status", "deleted"), context);
        assertFalse(result.error());
        assertTrue(result.content().contains("deleted"));
        assertEquals(0, store.size());
    }

    @Test
    void updateToolDeleteNonexistentReturnsError() {
        ToolResult result = updateTool.execute(
                Map.of("taskId", "999", "status", "deleted"), context);
        assertTrue(result.error());
        assertTrue(result.content().contains("not found"));
    }

    @Test
    void updateToolInvalidStatusReturnsError() {
        store.createTask("Task", "desc");
        ToolResult result = updateTool.execute(
                Map.of("taskId", "1", "status", "bogus"), context);
        assertTrue(result.error());
        assertTrue(result.content().contains("Unknown"));
    }

    @Test
    void updateToolMissingTaskIdReturnsError() {
        ToolResult result = updateTool.execute(Map.of("status", "completed"), context);
        assertTrue(result.error());
        assertTrue(result.content().contains("taskId"));
    }

    @Test
    void updateToolAddBlocks() {
        store.createTask("Task 1", "desc");
        store.createTask("Task 2", "desc");
        ToolResult result = updateTool.execute(
                Map.of("taskId", "1", "addBlocks", "2"), context);
        assertFalse(result.error());
        assertTrue(store.getTask("1").blocks().contains("2"));
    }

    @Test
    void updateToolAddBlockedBy() {
        store.createTask("Task 1", "desc");
        store.createTask("Task 2", "desc");
        ToolResult result = updateTool.execute(
                Map.of("taskId", "2", "addBlockedBy", "1"), context);
        assertFalse(result.error());
        assertTrue(store.getTask("2").blockedBy().contains("1"));
    }

    @Test
    void updateToolCommaSeparatedBlocks() {
        store.createTask("Task 1", "desc");
        store.createTask("Task 2", "desc");
        store.createTask("Task 3", "desc");
        ToolResult result = updateTool.execute(
                Map.of("taskId", "1", "addBlocks", "2, 3"), context);
        assertFalse(result.error());
        Task task = store.getTask("1");
        assertTrue(task.blocks().contains("2"));
        assertTrue(task.blocks().contains("3"));
    }

    @Test
    void updateToolNonexistentTaskReturnsError() {
        ToolResult result = updateTool.execute(
                Map.of("taskId", "999", "subject", "New"), context);
        assertTrue(result.error());
    }

    // ---- Metadata checks ----

    @Test
    void createToolMetadata() {
        assertEquals("task_create", createTool.metadata().name());
        assertFalse(createTool.metadata().readOnly());
        assertTrue(createTool.metadata().concurrencySafe());
    }

    @Test
    void getToolMetadata() {
        assertEquals("task_get", getTool.metadata().name());
        assertTrue(getTool.metadata().readOnly());
    }

    @Test
    void listToolMetadata() {
        assertEquals("task_list", listTool.metadata().name());
        assertTrue(listTool.metadata().readOnly());
    }

    @Test
    void updateToolMetadata() {
        assertEquals("task_update", updateTool.metadata().name());
        assertFalse(updateTool.metadata().readOnly());
    }
}
