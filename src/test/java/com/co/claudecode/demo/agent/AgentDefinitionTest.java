package com.co.claudecode.demo.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentDefinitionTest {

    @Test
    void builtInGeneralPurposeHasCorrectDefaults() {
        AgentDefinition gp = BuiltInAgents.GENERAL_PURPOSE;
        assertEquals("general-purpose", gp.agentType());
        assertEquals(AgentDefinition.Source.BUILT_IN, gp.source());
        assertNull(gp.allowedTools()); // wildcard
        assertTrue(gp.disallowedTools().isEmpty());
        assertFalse(gp.readOnly());
        assertEquals(12, gp.maxTurns());
    }

    @Test
    void builtInExploreIsReadOnly() {
        AgentDefinition explore = BuiltInAgents.EXPLORE;
        assertEquals("Explore", explore.agentType());
        assertTrue(explore.readOnly());
        assertNotNull(explore.allowedTools());
        assertTrue(explore.allowedTools().contains("list_files"));
        assertTrue(explore.allowedTools().contains("read_file"));
        assertTrue(explore.disallowedTools().contains("write_file"));
    }

    @Test
    void isToolAllowedWildcard() {
        AgentDefinition gp = BuiltInAgents.GENERAL_PURPOSE;
        assertTrue(gp.isToolAllowed("list_files"));
        assertTrue(gp.isToolAllowed("write_file"));
        assertTrue(gp.isToolAllowed("any_tool"));
    }

    @Test
    void isToolAllowedExplicitList() {
        AgentDefinition explore = BuiltInAgents.EXPLORE;
        assertTrue(explore.isToolAllowed("list_files"));
        assertTrue(explore.isToolAllowed("read_file"));
        assertFalse(explore.isToolAllowed("write_file")); // disallowed
        assertFalse(explore.isToolAllowed("agent")); // not in allowedTools
    }

    @Test
    void customAgentCreation() {
        AgentDefinition custom = AgentDefinition.custom(
                "my-agent", "Test agent", "You are a test agent.",
                List.of("list_files"), List.of(), true, 5, "sonnet");
        assertEquals("my-agent", custom.agentType());
        assertEquals(AgentDefinition.Source.CUSTOM, custom.source());
        assertEquals("sonnet", custom.model());
        assertEquals(5, custom.maxTurns());
        assertTrue(custom.readOnly());
    }

    @Test
    void blankAgentTypeThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                AgentDefinition.builtIn("", "desc", "prompt", null, null, false, 12));
    }

    @Test
    void negativeMaxTurnsDefaultsTo12() {
        AgentDefinition def = AgentDefinition.builtIn("test", "desc", "prompt",
                null, null, false, -1);
        assertEquals(12, def.maxTurns());
    }
}
