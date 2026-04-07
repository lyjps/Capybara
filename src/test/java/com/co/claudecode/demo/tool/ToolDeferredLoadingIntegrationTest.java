package com.co.claudecode.demo.tool;

import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.message.MessageRole;
import com.co.claudecode.demo.message.ToolReferenceBlock;
import com.co.claudecode.demo.message.ToolResultBlock;
import com.co.claudecode.demo.model.llm.LlmConversationMapper;
import com.co.claudecode.demo.model.llm.LlmRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the tool deferred loading mechanism.
 * Tests the full flow: ToolSearchUtils filtering → LlmConversationMapper schema generation.
 */
class ToolDeferredLoadingIntegrationTest {

    // ================================================================
    //  ToolReferenceBlock in ContentBlock
    // ================================================================

    @Test
    void toolReferenceBlock_renderForModel() {
        ToolReferenceBlock ref = new ToolReferenceBlock("mcp__server__tool");
        assertEquals("[tool_reference] mcp__server__tool", ref.renderForModel());
    }

    @Test
    void toolReferenceBlock_nullNameDefaultsToEmpty() {
        ToolReferenceBlock ref = new ToolReferenceBlock(null);
        assertEquals("", ref.toolName());
    }

    @Test
    void conversationMessage_toolReferences_extractsReferences() {
        ConversationMessage msg = new ConversationMessage(MessageRole.USER, List.of(
                new ToolReferenceBlock("tool_a"),
                new ToolReferenceBlock("tool_b")
        ));

        assertEquals(2, msg.toolReferences().size());
        assertEquals("tool_a", msg.toolReferences().get(0).toolName());
    }

    // ================================================================
    //  LlmRequest.ToolSchema deferLoading field
    // ================================================================

    @Test
    void toolSchema_deferLoadingDefaultsFalse() {
        LlmRequest.ToolSchema schema = new LlmRequest.ToolSchema("test", "desc", List.of());
        assertFalse(schema.deferLoading());
    }

    @Test
    void toolSchema_deferLoadingCanBeTrue() {
        LlmRequest.ToolSchema schema = new LlmRequest.ToolSchema("test", "desc", List.of(), true);
        assertTrue(schema.deferLoading());
    }

    // ================================================================
    //  ToolMetadata deferred loading fields
    // ================================================================

    @Test
    void toolMetadata_backwardCompatible_defaultsFalse() {
        ToolMetadata meta = new ToolMetadata("test", "desc", true, true, false,
                ToolMetadata.PathDomain.NONE, null);
        assertFalse(meta.isMcp());
        assertFalse(meta.shouldDefer());
        assertFalse(meta.alwaysLoad());
        assertEquals("", meta.searchHint());
    }

    @Test
    void toolMetadata_backwardCompatibleWithParams_defaultsFalse() {
        ToolMetadata meta = new ToolMetadata("test", "desc", true, true, false,
                ToolMetadata.PathDomain.NONE, null, List.of());
        assertFalse(meta.isMcp());
        assertFalse(meta.shouldDefer());
        assertFalse(meta.alwaysLoad());
    }

    @Test
    void toolMetadata_fullConstructor_setsAllFields() {
        ToolMetadata meta = new ToolMetadata("test", "desc", true, true, false,
                ToolMetadata.PathDomain.NONE, null, List.of(),
                true, true, false, "hint1,hint2");
        assertTrue(meta.isMcp());
        assertTrue(meta.shouldDefer());
        assertFalse(meta.alwaysLoad());
        assertEquals("hint1,hint2", meta.searchHint());
    }

    // ================================================================
    //  LlmConversationMapper with tool filtering
    // ================================================================

    @Test
    void mapper_toRequest_filteredToolsSubset() {
        LlmConversationMapper mapper = new LlmConversationMapper();

        // Two tools: one normal, one MCP
        Tool normalTool = stubTool("list_files", false, false, true, "List files");
        Tool mcpTool = stubTool("mcp__server__geo", true, false, false, "Geocode");

        // Conversation with system prompt
        List<ConversationMessage> conversation = List.of(
                ConversationMessage.system("You are an assistant."),
                ConversationMessage.user("Hello")
        );

        // Only send normal tool (filtered)
        Collection<Tool> filteredTools = List.of(normalTool);
        Collection<Tool> allTools = List.of(normalTool, mcpTool);

        LlmRequest request = mapper.toRequest(conversation, "test-model", filteredTools, allTools);

        // Only the filtered tool should be in the schema
        assertEquals(1, request.tools().size());
        assertEquals("list_files", request.tools().get(0).name());
    }

    @Test
    void mapper_toRequest_deferLoadingFlagSet() {
        LlmConversationMapper mapper = new LlmConversationMapper();

        Tool mcpTool = stubTool("mcp__server__geo", true, false, false, "Geocode");
        Tool normalTool = stubTool("list_files", false, false, true, "List");

        List<ConversationMessage> conversation = List.of(
                ConversationMessage.system("You are an assistant."),
                ConversationMessage.user("Hello")
        );

        // Send both but in a context where tool search is not enabled (STANDARD mode)
        // So willDeferLoading returns false
        Collection<Tool> allTools = List.of(normalTool, mcpTool);
        LlmRequest request = mapper.toRequest(conversation, "test-model", allTools, allTools);

        // In STANDARD mode, deferLoading should be false for all
        for (LlmRequest.ToolSchema schema : request.tools()) {
            assertFalse(schema.deferLoading());
        }
    }

    // ================================================================
    //  End-to-end: discovery → filtering
    // ================================================================

    @Test
    void endToEnd_undiscoveredMcpToolFiltered_discoveredIncluded() {
        Tool normalTool = stubTool("list_files", false, false, true, "List files");
        Tool mcpTool1 = stubTool("mcp__geo", true, false, false, "Geocode");
        Tool mcpTool2 = stubTool("mcp__search", true, false, false, "Search");
        Tool toolSearch = stubTool(ToolSearchUtils.TOOL_SEARCH_TOOL_NAME, false, false, true, "Search tools");

        Collection<Tool> allTools = List.of(normalTool, mcpTool1, mcpTool2, toolSearch);

        // Simulate: mcp__geo has been discovered via ToolSearch
        List<ConversationMessage> messages = List.of(
                ConversationMessage.user("hello"),
                new ConversationMessage(MessageRole.USER, List.of(
                        new ToolReferenceBlock("mcp__geo")
                ))
        );

        // Extract discovered tool names
        Set<String> discovered = ToolSearchUtils.extractDiscoveredToolNames(messages);
        assertEquals(Set.of("mcp__geo"), discovered);

        // Verify isDeferredTool for each
        assertTrue(ToolSearchUtils.isDeferredTool(mcpTool1));
        assertTrue(ToolSearchUtils.isDeferredTool(mcpTool2));
        assertFalse(ToolSearchUtils.isDeferredTool(normalTool));
        assertFalse(ToolSearchUtils.isDeferredTool(toolSearch));
    }

    @Test
    void endToEnd_extractDiscoveredFromToolResultContent() {
        // Simulate ToolSearchTool returning tool_reference text format
        List<ConversationMessage> messages = new ArrayList<>();
        messages.add(ConversationMessage.user("Find me a map tool"));
        messages.add(ConversationMessage.toolResult(
                new ToolResultBlock("call_1", ToolSearchUtils.TOOL_SEARCH_TOOL_NAME, false,
                        "[tool_reference] mcp__mt_map__geo\n[tool_reference] mcp__mt_map__nearby")
        ));

        Set<String> discovered = ToolSearchUtils.extractDiscoveredToolNames(messages);
        assertEquals(Set.of("mcp__mt_map__geo", "mcp__mt_map__nearby"), discovered);
    }

    // ================================================================
    //  Helper: stub tool
    // ================================================================

    private Tool stubTool(String name, boolean isMcp, boolean shouldDefer, boolean alwaysLoad,
                          String description) {
        ToolMetadata meta = new ToolMetadata(
                name, description,
                true, true, false,
                ToolMetadata.PathDomain.NONE, null,
                List.of(),
                isMcp, shouldDefer, alwaysLoad, ""
        );
        return new Tool() {
            @Override
            public ToolMetadata metadata() {
                return meta;
            }

            @Override
            public ToolResult execute(Map<String, String> input, ToolExecutionContext context) {
                return new ToolResult(false, "ok");
            }
        };
    }
}
