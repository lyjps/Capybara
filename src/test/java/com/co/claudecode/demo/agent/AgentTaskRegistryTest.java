package com.co.claudecode.demo.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentTaskRegistryTest {

    private AgentTaskRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AgentTaskRegistry();
    }

    @Test
    void registerAndFindById() {
        AgentTask task = new AgentTask("abc123", null, "general-purpose", "do something");
        registry.register(task);
        assertSame(task, registry.findById("abc123"));
    }

    @Test
    void registerAndFindByName() {
        AgentTask task = new AgentTask("abc123", "worker-1", "general-purpose", "do something");
        registry.register(task);
        assertSame(task, registry.findByName("worker-1"));
    }

    @Test
    void findByIdOrNameWorksForBoth() {
        AgentTask task = new AgentTask("abc123", "worker-1", "general-purpose", "do something");
        registry.register(task);
        assertSame(task, registry.findByIdOrName("abc123"));
        assertSame(task, registry.findByIdOrName("worker-1"));
    }

    @Test
    void sendMessageDelivers() {
        AgentTask task = new AgentTask("abc123", "worker-1", "general-purpose", "do something");
        registry.register(task);

        assertTrue(registry.sendMessage("worker-1", "hello"));
        assertTrue(task.hasPendingMessages());

        var messages = task.drainMessages();
        assertEquals(1, messages.size());
        assertEquals("hello", messages.get(0));
    }

    @Test
    void sendMessageToUnknownReturnsFalse() {
        assertFalse(registry.sendMessage("unknown", "hello"));
    }

    @Test
    void allRunningFiltersCorrectly() {
        AgentTask running = new AgentTask("a1", null, "gp", "p1");
        AgentTask completed = new AgentTask("a2", null, "gp", "p2");
        completed.markCompleted(AgentResult.completed("a2", "gp", "done", 0, 100, 0));

        registry.register(running);
        registry.register(completed);

        assertEquals(1, registry.allRunning().size());
        assertEquals("a1", registry.allRunning().get(0).agentId());
    }

    @Test
    void cleanupRemovesNonRunning() {
        AgentTask running = new AgentTask("a1", null, "gp", "p1");
        AgentTask completed = new AgentTask("a2", "done-agent", "gp", "p2");
        completed.markCompleted(AgentResult.completed("a2", "gp", "done", 0, 100, 0));

        registry.register(running);
        registry.register(completed);
        assertEquals(2, registry.size());

        int cleaned = registry.cleanup();
        assertEquals(1, cleaned);
        assertEquals(1, registry.size());
        assertNotNull(registry.findById("a1"));
        assertNull(registry.findById("a2"));
    }

    @Test
    void generateAgentIdIsUnique() {
        String id1 = AgentTaskRegistry.generateAgentId();
        String id2 = AgentTaskRegistry.generateAgentId();
        assertNotEquals(id1, id2);
        assertEquals(8, id1.length());
    }
}
