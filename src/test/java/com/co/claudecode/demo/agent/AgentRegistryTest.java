package com.co.claudecode.demo.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentRegistryTest {

    @Test
    void withBuiltInsRegistersAllBuiltInAgents() {
        AgentRegistry registry = AgentRegistry.withBuiltIns();
        assertEquals(2, registry.size());
        assertNotNull(registry.resolve("general-purpose"));
        assertNotNull(registry.resolve("Explore"));
    }

    @Test
    void resolveUnknownThrows() {
        AgentRegistry registry = new AgentRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.resolve("unknown"));
    }

    @Test
    void findOrNullReturnsNullForUnknown() {
        AgentRegistry registry = new AgentRegistry();
        assertNull(registry.findOrNull("unknown"));
    }

    @Test
    void registerOverwritesSameName() {
        AgentRegistry registry = new AgentRegistry();
        AgentDefinition def1 = AgentDefinition.builtIn("test", "v1", "p1", null, null, false, 12);
        AgentDefinition def2 = AgentDefinition.builtIn("test", "v2", "p2", null, null, false, 12);
        registry.register(def1);
        registry.register(def2);
        assertEquals(1, registry.size());
        assertEquals("v2", registry.resolve("test").whenToUse());
    }

    @Test
    void allDefinitionsReturnsImmutableCopy() {
        AgentRegistry registry = AgentRegistry.withBuiltIns();
        var defs = registry.allDefinitions();
        assertEquals(2, defs.size());
    }
}
