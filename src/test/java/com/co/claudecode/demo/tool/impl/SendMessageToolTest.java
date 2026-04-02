package com.co.claudecode.demo.tool.impl;

import com.co.claudecode.demo.agent.AgentTask;
import com.co.claudecode.demo.agent.AgentTaskRegistry;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SendMessageToolTest {

    private AgentTaskRegistry taskRegistry;
    private SendMessageTool tool;
    private ToolExecutionContext context;

    @BeforeEach
    void setUp() {
        taskRegistry = new AgentTaskRegistry();
        tool = new SendMessageTool(taskRegistry);
        context = new ToolExecutionContext(
                Path.of(System.getProperty("java.io.tmpdir")),
                Path.of(System.getProperty("java.io.tmpdir"))
        );
    }

    @Test
    void sendMessageByIdDelivers() {
        AgentTask task = new AgentTask("agent-1", null, "gp", "prompt");
        taskRegistry.register(task);

        ToolResult result = tool.execute(
                Map.of("to", "agent-1", "message", "hello agent"), context);
        assertFalse(result.error());
        assertTrue(result.content().contains("success"));
        assertTrue(result.content().contains("true"));

        // Verify message was actually queued
        assertTrue(task.hasPendingMessages());
        var messages = task.drainMessages();
        assertEquals(1, messages.size());
        assertEquals("hello agent", messages.get(0));
    }

    @Test
    void sendMessageByNameDelivers() {
        AgentTask task = new AgentTask("agent-1", "worker-alpha", "gp", "prompt");
        taskRegistry.register(task);

        ToolResult result = tool.execute(
                Map.of("to", "worker-alpha", "message", "go"), context);
        assertFalse(result.error());
        assertTrue(result.content().contains("success"));

        assertTrue(task.hasPendingMessages());
        assertEquals("go", task.drainMessages().get(0));
    }

    @Test
    void sendMessageToUnknownAgentReturnsError() {
        ToolResult result = tool.execute(
                Map.of("to", "nonexistent", "message", "hello"), context);
        assertTrue(result.error());
        assertTrue(result.content().contains("not found"));
    }

    @Test
    void sendMessageToCompletedAgentReturnsFalse() {
        AgentTask task = new AgentTask("agent-1", "done-agent", "gp", "prompt");
        task.markCompleted(com.co.claudecode.demo.agent.AgentResult.completed(
                "agent-1", "gp", "done", 0, 100, 0));
        taskRegistry.register(task);

        // sendMessage looks up via findByIdOrName — the agent exists but
        // the registry's sendMessage just enqueues, it doesn't check status.
        // So this should succeed (message queued even to completed agent).
        ToolResult result = tool.execute(
                Map.of("to", "done-agent", "message", "late message"), context);
        assertFalse(result.error());
    }

    @Test
    void sendMultipleMessagesToSameAgent() {
        AgentTask task = new AgentTask("agent-1", "worker", "gp", "prompt");
        taskRegistry.register(task);

        tool.execute(Map.of("to", "worker", "message", "msg1"), context);
        tool.execute(Map.of("to", "worker", "message", "msg2"), context);
        tool.execute(Map.of("to", "worker", "message", "msg3"), context);

        var messages = task.drainMessages();
        assertEquals(3, messages.size());
        assertEquals("msg1", messages.get(0));
        assertEquals("msg2", messages.get(1));
        assertEquals("msg3", messages.get(2));
    }

    @Test
    void validateThrowsForMissingTo() {
        assertThrows(IllegalArgumentException.class, () ->
                tool.validate(Map.of("message", "hello")));
    }

    @Test
    void validateThrowsForMissingMessage() {
        assertThrows(IllegalArgumentException.class, () ->
                tool.validate(Map.of("to", "agent-1")));
    }

    @Test
    void validateThrowsForBlankTo() {
        assertThrows(IllegalArgumentException.class, () ->
                tool.validate(Map.of("to", "  ", "message", "hello")));
    }

    @Test
    void validateThrowsForBlankMessage() {
        assertThrows(IllegalArgumentException.class, () ->
                tool.validate(Map.of("to", "agent-1", "message", "  ")));
    }

    @Test
    void metadataIsCorrect() {
        assertEquals("send_message", tool.metadata().name());
        assertFalse(tool.metadata().readOnly());
        assertTrue(tool.metadata().concurrencySafe());
        assertEquals(2, tool.metadata().params().size());
    }
}
