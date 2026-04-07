package com.co.claudecode.demo.tool;

import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.message.ContentBlock;
import com.co.claudecode.demo.message.MessageRole;
import com.co.claudecode.demo.message.TextBlock;
import com.co.claudecode.demo.message.ToolReferenceBlock;
import com.co.claudecode.demo.message.ToolResultBlock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ToolSearchUtils} — the core utility for tool deferred loading.
 */
class ToolSearchUtilsTest {

    // ================================================================
    //  isDeferredTool() tests
    // ================================================================

    @Test
    void isDeferredTool_alwaysLoadTool_neverDeferred() {
        Tool tool = stubTool("my_tool", false, false, true); // alwaysLoad=true
        assertFalse(ToolSearchUtils.isDeferredTool(tool));
    }

    @Test
    void isDeferredTool_mcpTool_alwaysDeferred() {
        Tool tool = stubTool("mcp__server__tool", true, false, false); // isMcp=true
        assertTrue(ToolSearchUtils.isDeferredTool(tool));
    }

    @Test
    void isDeferredTool_mcpToolWithAlwaysLoad_notDeferred() {
        // alwaysLoad takes priority over isMcp
        Tool tool = stubTool("mcp__server__tool", true, false, true);
        assertFalse(ToolSearchUtils.isDeferredTool(tool));
    }

    @Test
    void isDeferredTool_toolSearchItself_neverDeferred() {
        Tool tool = stubTool(ToolSearchUtils.TOOL_SEARCH_TOOL_NAME, false, false, false);
        assertFalse(ToolSearchUtils.isDeferredTool(tool));
    }

    @Test
    void isDeferredTool_shouldDefer_isDeferred() {
        Tool tool = stubTool("my_tool", false, true, false); // shouldDefer=true
        assertTrue(ToolSearchUtils.isDeferredTool(tool));
    }

    @Test
    void isDeferredTool_normalTool_notDeferred() {
        Tool tool = stubTool("list_files", false, false, false);
        assertFalse(ToolSearchUtils.isDeferredTool(tool));
    }

    // ================================================================
    //  extractDiscoveredToolNames() tests
    // ================================================================

    @Test
    void extractDiscoveredToolNames_findsToolReferencesInUserMessages() {
        List<ConversationMessage> messages = List.of(
                ConversationMessage.user("hello"),
                new ConversationMessage(MessageRole.USER, List.of(
                        new ToolReferenceBlock("mcp__server__tool_a"),
                        new ToolReferenceBlock("mcp__server__tool_b")
                )),
                ConversationMessage.assistant("ok", List.of())
        );

        Set<String> discovered = ToolSearchUtils.extractDiscoveredToolNames(messages);
        assertEquals(Set.of("mcp__server__tool_a", "mcp__server__tool_b"), discovered);
    }

    @Test
    void extractDiscoveredToolNames_parsesToolReferenceTextInToolResults() {
        List<ConversationMessage> messages = List.of(
                ConversationMessage.toolResult(
                        new ToolResultBlock("id1", "ToolSearch", false,
                                "[tool_reference] mcp__server__geo\n[tool_reference] mcp__server__regeo")
                )
        );

        Set<String> discovered = ToolSearchUtils.extractDiscoveredToolNames(messages);
        assertTrue(discovered.contains("mcp__server__geo"));
        assertTrue(discovered.contains("mcp__server__regeo"));
    }

    @Test
    void extractDiscoveredToolNames_emptyMessages_emptySet() {
        Set<String> discovered = ToolSearchUtils.extractDiscoveredToolNames(List.of());
        assertTrue(discovered.isEmpty());
    }

    @Test
    void extractDiscoveredToolNames_noReferences_emptySet() {
        List<ConversationMessage> messages = List.of(
                ConversationMessage.user("hello"),
                ConversationMessage.assistant("world", List.of())
        );

        Set<String> discovered = ToolSearchUtils.extractDiscoveredToolNames(messages);
        assertTrue(discovered.isEmpty());
    }

    @Test
    void extractDiscoveredToolNames_ignoresErrorToolResults() {
        List<ConversationMessage> messages = List.of(
                ConversationMessage.toolResult(
                        new ToolResultBlock("id1", "ToolSearch", true,
                                "[tool_reference] should_not_discover")
                )
        );

        Set<String> discovered = ToolSearchUtils.extractDiscoveredToolNames(messages);
        assertTrue(discovered.isEmpty());
    }

    // ================================================================
    //  filterToolsForApi() tests
    // ================================================================

    @Test
    void filterToolsForApi_whenSearchDisabled_excludesToolSearch() {
        // STANDARD mode (default) — ToolSearch excluded, everything else included
        Tool normalTool = stubTool("list_files", false, false, false);
        Tool toolSearch = stubTool(ToolSearchUtils.TOOL_SEARCH_TOOL_NAME, false, false, true);
        Tool mcpTool = stubTool("mcp__server__tool", true, false, false);

        List<Tool> filtered = ToolSearchUtils.filterToolsForApi(
                List.of(normalTool, toolSearch, mcpTool),
                List.of()
        );

        List<String> names = filtered.stream().map(t -> t.metadata().name()).toList();
        assertTrue(names.contains("list_files"));
        assertTrue(names.contains("mcp__server__tool"));
        assertFalse(names.contains(ToolSearchUtils.TOOL_SEARCH_TOOL_NAME));
    }

    // ================================================================
    //  isToolSearchToolAvailable() tests
    // ================================================================

    @Test
    void isToolSearchToolAvailable_present_true() {
        Tool toolSearch = stubTool(ToolSearchUtils.TOOL_SEARCH_TOOL_NAME, false, false, true);
        assertTrue(ToolSearchUtils.isToolSearchToolAvailable(List.of(toolSearch)));
    }

    @Test
    void isToolSearchToolAvailable_absent_false() {
        Tool normalTool = stubTool("list_files", false, false, false);
        assertFalse(ToolSearchUtils.isToolSearchToolAvailable(List.of(normalTool)));
    }

    // ================================================================
    //  willDeferLoading() tests
    // ================================================================

    @Test
    void willDeferLoading_standardMode_alwaysFalse() {
        // In STANDARD mode, isToolSearchEnabled returns false
        Tool mcpTool = stubTool("mcp__server__tool", true, false, false);
        // Standard mode → willDeferLoading should be false (since isToolSearchEnabled is false)
        boolean result = ToolSearchUtils.willDeferLoading(mcpTool, List.of(mcpTool));
        assertFalse(result); // STANDARD mode, tool search not enabled
    }

    // ================================================================
    //  buildSchemaNotSentHint() tests
    // ================================================================

    @Test
    void buildSchemaNotSentHint_whenSearchDisabled_returnsNull() {
        Tool mcpTool = stubTool("mcp__server__tool", true, false, false);
        String hint = ToolSearchUtils.buildSchemaNotSentHint(mcpTool, List.of(), List.of(mcpTool));
        assertNull(hint); // STANDARD mode
    }

    @Test
    void buildSchemaNotSentHint_nonDeferredTool_returnsNull() {
        Tool normalTool = stubTool("list_files", false, false, false);
        assertNull(ToolSearchUtils.buildSchemaNotSentHint(normalTool, List.of(), List.of(normalTool)));
    }

    // ================================================================
    //  checkAutoThreshold() tests
    // ================================================================

    @Test
    void checkAutoThreshold_fewSmallTools_belowThreshold() {
        List<Tool> tools = new ArrayList<>();
        tools.add(stubTool("small_mcp_tool", true, false, false, "short desc"));
        assertFalse(ToolSearchUtils.checkAutoThreshold(tools));
    }

    @Test
    void checkAutoThreshold_manyLargeTools_aboveThreshold() {
        List<Tool> tools = new ArrayList<>();
        // Create many MCP tools with long descriptions to exceed threshold
        // Threshold: 200000 * 0.10 = 20000 tokens = 50000 chars
        String longDesc = "x".repeat(5000);
        for (int i = 0; i < 15; i++) {
            tools.add(stubTool("mcp__server__tool_" + i, true, false, false, longDesc));
        }
        assertTrue(ToolSearchUtils.checkAutoThreshold(tools));
    }

    // ================================================================
    //  Helper: stub tool
    // ================================================================

    private Tool stubTool(String name, boolean isMcp, boolean shouldDefer, boolean alwaysLoad) {
        return stubTool(name, isMcp, shouldDefer, alwaysLoad, "A test tool");
    }

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
