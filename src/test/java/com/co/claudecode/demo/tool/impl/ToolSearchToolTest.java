package com.co.claudecode.demo.tool.impl;

import com.co.claudecode.demo.tool.Tool;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolMetadata;
import com.co.claudecode.demo.tool.ToolResult;
import com.co.claudecode.demo.tool.ToolSearchUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ToolSearchTool} — the tool discovery mechanism for deferred loading.
 */
class ToolSearchToolTest {

    private List<Tool> allTools;
    private ToolSearchTool toolSearchTool;
    private ToolExecutionContext context;

    @BeforeEach
    void setUp() {
        allTools = new ArrayList<>();

        // Add some normal tools (not deferred)
        allTools.add(stubTool("list_files", false, false, true, "List files in a directory"));
        allTools.add(stubTool("read_file", false, false, true, "Read file contents"));

        // Add MCP tools (deferred by default via isMcp=true)
        allTools.add(stubTool("mcp__xt_search__meituan_search",
                true, false, false, "Search Meituan platform for local services",
                "meituan,search,local,restaurant,hotel"));
        allTools.add(stubTool("mcp__xt_search__content_search",
                true, false, false, "Search content including web and UGC",
                "content,ugc,review,article"));
        allTools.add(stubTool("mcp__mt_map__geo",
                true, false, false, "Geocoding: convert address to coordinates",
                "geo,geocode,address,coordinate,location"));
        allTools.add(stubTool("mcp__mt_map__nearby",
                true, false, false, "Search nearby points of interest",
                "nearby,poi,surrounding,around"));
        allTools.add(stubTool("mcp__mt_map__direction",
                true, false, false, "Route planning for driving walking cycling",
                "direction,route,navigate,driving,walking"));

        toolSearchTool = new ToolSearchTool(allTools);
        context = new ToolExecutionContext(
                Path.of(System.getProperty("java.io.tmpdir")),
                Path.of(System.getProperty("java.io.tmpdir"))
        );
    }

    // ================================================================
    //  Metadata tests
    // ================================================================

    @Test
    void metadata_nameIsToolSearch() {
        assertEquals(ToolSearchUtils.TOOL_SEARCH_TOOL_NAME, toolSearchTool.metadata().name());
    }

    @Test
    void metadata_isAlwaysLoad() {
        assertTrue(toolSearchTool.metadata().alwaysLoad());
    }

    @Test
    void metadata_isNotMcp() {
        assertFalse(toolSearchTool.metadata().isMcp());
    }

    @Test
    void metadata_isNeverDeferred() {
        assertFalse(ToolSearchUtils.isDeferredTool(toolSearchTool));
    }

    // ================================================================
    //  Direct select mode tests
    // ================================================================

    @Test
    void execute_selectSingleTool_returnsToolReference() throws Exception {
        ToolResult result = toolSearchTool.execute(
                Map.of("query", "select:mcp__mt_map__geo"), context);

        assertFalse(result.error());
        assertTrue(result.content().contains("[tool_reference] mcp__mt_map__geo"));
    }

    @Test
    void execute_selectMultipleTools_returnsAll() throws Exception {
        ToolResult result = toolSearchTool.execute(
                Map.of("query", "select:mcp__mt_map__geo,mcp__mt_map__nearby"), context);

        assertFalse(result.error());
        assertTrue(result.content().contains("[tool_reference] mcp__mt_map__geo"));
        assertTrue(result.content().contains("[tool_reference] mcp__mt_map__nearby"));
    }

    @Test
    void execute_selectNonExistentDeferredTool_noResults() throws Exception {
        ToolResult result = toolSearchTool.execute(
                Map.of("query", "select:nonexistent_tool"), context);

        assertFalse(result.error());
        assertTrue(result.content().contains("No matching deferred tools"));
    }

    @Test
    void execute_selectNonDeferredTool_noResults() throws Exception {
        // list_files is not deferred (alwaysLoad=true), so select won't find it
        ToolResult result = toolSearchTool.execute(
                Map.of("query", "select:list_files"), context);

        assertFalse(result.error());
        assertTrue(result.content().contains("No matching deferred tools"));
    }

    @Test
    void execute_selectWithExtraSpaces_stillWorks() throws Exception {
        ToolResult result = toolSearchTool.execute(
                Map.of("query", "select: mcp__mt_map__geo , mcp__mt_map__nearby "), context);

        assertFalse(result.error());
        assertTrue(result.content().contains("[tool_reference] mcp__mt_map__geo"));
        assertTrue(result.content().contains("[tool_reference] mcp__mt_map__nearby"));
    }

    // ================================================================
    //  Keyword search mode tests
    // ================================================================

    @Test
    void execute_searchByToolName_findsExactMatch() throws Exception {
        ToolResult result = toolSearchTool.execute(
                Map.of("query", "geo"), context);

        assertFalse(result.error());
        assertTrue(result.content().contains("[tool_reference] mcp__mt_map__geo"));
    }

    @Test
    void execute_searchByDescription_findsMatch() throws Exception {
        ToolResult result = toolSearchTool.execute(
                Map.of("query", "geocoding address"), context);

        assertFalse(result.error());
        assertTrue(result.content().contains("[tool_reference] mcp__mt_map__geo"));
    }

    @Test
    void execute_searchBySearchHint_findsMatch() throws Exception {
        ToolResult result = toolSearchTool.execute(
                Map.of("query", "navigate"), context);

        assertFalse(result.error());
        assertTrue(result.content().contains("[tool_reference] mcp__mt_map__direction"));
    }

    @Test
    void execute_searchMeituan_findsSearchTool() throws Exception {
        ToolResult result = toolSearchTool.execute(
                Map.of("query", "meituan search"), context);

        assertFalse(result.error());
        assertTrue(result.content().contains("[tool_reference] mcp__xt_search__meituan_search"));
    }

    @Test
    void execute_searchNoMatch_returnsEmptyMessage() throws Exception {
        ToolResult result = toolSearchTool.execute(
                Map.of("query", "xyznonexistent123"), context);

        assertFalse(result.error());
        assertTrue(result.content().contains("No matching deferred tools"));
    }

    @Test
    void execute_searchWithMaxResults_limitsOutput() throws Exception {
        ToolResult result = toolSearchTool.execute(
                Map.of("query", "mcp", "max_results", "2"), context);

        assertFalse(result.error());
        // Count tool_reference lines
        long refCount = result.content().lines()
                .filter(l -> l.contains("[tool_reference]"))
                .count();
        assertTrue(refCount <= 2);
    }

    // ================================================================
    //  Edge cases
    // ================================================================

    @Test
    void execute_emptyQuery_returnsError() throws Exception {
        ToolResult result = toolSearchTool.execute(
                Map.of("query", ""), context);

        assertTrue(result.error());
        assertTrue(result.content().contains("required"));
    }

    @Test
    void execute_missingQuery_returnsError() throws Exception {
        ToolResult result = toolSearchTool.execute(Map.of(), context);

        assertTrue(result.error());
    }

    @Test
    void execute_invalidMaxResults_usesDefault() throws Exception {
        ToolResult result = toolSearchTool.execute(
                Map.of("query", "mcp", "max_results", "invalid"), context);

        // Should not error, uses default max_results
        assertFalse(result.error());
    }

    // ================================================================
    //  Description contains deferred tool list
    // ================================================================

    @Test
    void description_containsDeferredToolNames() {
        String desc = toolSearchTool.metadata().description();
        assertTrue(desc.contains("mcp__xt_search__meituan_search"));
        assertTrue(desc.contains("mcp__mt_map__geo"));
        assertFalse(desc.contains("list_files")); // not deferred
    }

    // ================================================================
    //  Helper: stub tool
    // ================================================================

    private Tool stubTool(String name, boolean isMcp, boolean shouldDefer, boolean alwaysLoad,
                          String description) {
        return stubTool(name, isMcp, shouldDefer, alwaysLoad, description, "");
    }

    private Tool stubTool(String name, boolean isMcp, boolean shouldDefer, boolean alwaysLoad,
                          String description, String searchHint) {
        ToolMetadata meta = new ToolMetadata(
                name, description,
                true, true, false,
                ToolMetadata.PathDomain.NONE, null,
                List.of(),
                isMcp, shouldDefer, alwaysLoad, searchHint
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
