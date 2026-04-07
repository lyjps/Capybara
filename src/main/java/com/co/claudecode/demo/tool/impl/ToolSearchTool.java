package com.co.claudecode.demo.tool.impl;

import com.co.claudecode.demo.tool.Tool;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolMetadata;
import com.co.claudecode.demo.tool.ToolResult;
import com.co.claudecode.demo.tool.ToolSearchUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ToolSearch 工具 — 工具延迟加载的核心发现机制。
 * <p>
 * 当延迟加载启用时，大量工具的 schema 不会发送给模型。
 * 模型通过调用此工具来搜索和发现需要使用的工具。
 * 发现后，后续 API 调用会包含这些工具的完整 schema。
 * <p>
 * 两种使用模式：
 * <ol>
 *   <li><b>直接选择</b>：{@code select:<tool_name>} 或 {@code select:<name1>,<name2>}</li>
 *   <li><b>关键词搜索</b>：基于评分的模糊匹配（名称、描述、searchHint）</li>
 * </ol>
 * <p>
 * 返回 {@code tool_reference} 格式的内容，由 {@code extractDiscoveredToolNames()} 扫描识别。
 * <p>
 * 对应 TS {@code tools/ToolSearchTool/ToolSearchTool.ts}。
 */
public final class ToolSearchTool implements Tool {

    /** "select:" 前缀，用于直接选择模式。 */
    private static final String SELECT_PREFIX = "select:";

    /** 默认最大返回结果数。 */
    private static final int DEFAULT_MAX_RESULTS = 5;

    // 关键词搜索评分权重（对应 TS 的 scoring 逻辑）
    private static final int SCORE_NAME_EXACT = 12;
    private static final int SCORE_NAME_PART_EXACT = 10;
    private static final int SCORE_NAME_PARTIAL = 6;
    private static final int SCORE_NAME_PART_PARTIAL = 5;
    private static final int SCORE_SEARCH_HINT = 4;
    private static final int SCORE_DESCRIPTION = 2;

    private final ToolMetadata metadata;
    private final Collection<Tool> allTools;

    /**
     * @param allTools 全量工具集合（用于搜索）
     */
    public ToolSearchTool(Collection<Tool> allTools) {
        this.allTools = allTools;
        this.metadata = new ToolMetadata(
                ToolSearchUtils.TOOL_SEARCH_TOOL_NAME,
                buildDescription(),
                true,           // readOnly
                true,           // concurrencySafe
                false,          // destructive
                ToolMetadata.PathDomain.NONE,
                null,           // pathInputKey
                List.of(
                        new ToolMetadata.ParamInfo("query",
                                "Search query for finding tools. Use 'select:<tool_name>' for direct selection, "
                                        + "or keywords for fuzzy search. Supports comma-separated for multi-select: "
                                        + "'select:tool1,tool2'.",
                                true),
                        new ToolMetadata.ParamInfo("max_results",
                                "Maximum number of results to return (default: 5).",
                                false)
                ),
                false,          // isMcp
                false,          // shouldDefer
                true,           // alwaysLoad — ToolSearch 永远不被延迟
                ""              // searchHint
        );
    }

    @Override
    public ToolMetadata metadata() {
        return metadata;
    }

    @Override
    public ToolResult execute(Map<String, String> input, ToolExecutionContext context) throws Exception {
        String query = input.getOrDefault("query", "").trim();
        int maxResults = parseMaxResults(input.getOrDefault("max_results", ""));

        if (query.isEmpty()) {
            return new ToolResult(true, "Query parameter is required.");
        }

        // 只在延迟工具池中搜索
        List<Tool> deferredTools = allTools.stream()
                .filter(ToolSearchUtils::isDeferredTool)
                .toList();

        List<String> matches;

        if (query.startsWith(SELECT_PREFIX)) {
            // 直接选择模式
            matches = handleDirectSelect(query, deferredTools);
        } else {
            // 关键词搜索模式
            matches = searchToolsWithKeywords(query, deferredTools, maxResults);
        }

        if (matches.isEmpty()) {
            return new ToolResult(false,
                    "No matching deferred tools found for query: \"" + query + "\". "
                            + "Try different search terms or use 'select:<exact_tool_name>' for direct selection.");
        }

        // 构建 tool_reference 格式的输出
        // 每行一个 [tool_reference] toolName，由 extractDiscoveredToolNames() 解析
        StringBuilder sb = new StringBuilder();
        for (String name : matches) {
            sb.append("[tool_reference] ").append(name).append("\n");
        }

        return new ToolResult(false, sb.toString().trim());
    }

    // ================================================================
    //  直接选择模式
    // ================================================================

    /**
     * 处理 "select:name1,name2" 格式的直接选择。
     */
    private List<String> handleDirectSelect(String query, List<Tool> deferredTools) {
        String namesPart = query.substring(SELECT_PREFIX.length()).trim();
        String[] requestedNames = namesPart.split(",");

        // 构建延迟工具名集合用于验证
        Set<String> deferredNameSet = new HashSet<>();
        for (Tool tool : deferredTools) {
            deferredNameSet.add(tool.metadata().name());
        }

        // 使用 LinkedHashSet 保持顺序并去重
        Set<String> matches = new LinkedHashSet<>();
        for (String name : requestedNames) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty() && deferredNameSet.contains(trimmed)) {
                matches.add(trimmed);
            }
        }

        return new ArrayList<>(matches);
    }

    // ================================================================
    //  关键词搜索模式
    // ================================================================

    /**
     * 基于评分的关键词搜索。
     * <p>
     * 评分维度（对应 TS searchToolsWithKeywords 的评分系统）：
     * <ul>
     *   <li>工具名完全匹配某个 term → 12 分</li>
     *   <li>工具名的某个部分完全匹配 → 10 分</li>
     *   <li>工具名包含 term 子串 → 6 分</li>
     *   <li>工具名的某个部分包含 term 子串 → 5 分</li>
     *   <li>searchHint 包含 term → 4 分</li>
     *   <li>描述中包含 term（词边界） → 2 分</li>
     * </ul>
     */
    private List<String> searchToolsWithKeywords(String query, List<Tool> deferredTools, int maxResults) {
        String[] terms = tokenizeQuery(query);
        if (terms.length == 0) {
            return List.of();
        }

        // 编译正则模式用于词边界匹配
        Pattern[] termPatterns = new Pattern[terms.length];
        for (int i = 0; i < terms.length; i++) {
            termPatterns[i] = Pattern.compile("\\b" + Pattern.quote(terms[i]) + "\\b",
                    Pattern.CASE_INSENSITIVE);
        }

        record ScoredTool(String name, int score) {}

        List<ScoredTool> scored = new ArrayList<>();
        for (Tool tool : deferredTools) {
            ToolMetadata meta = tool.metadata();
            String toolName = meta.name().toLowerCase();
            String description = meta.description().toLowerCase();
            String searchHint = meta.searchHint().toLowerCase();

            // 解析工具名的各部分（按 _ 和 - 分割）
            String[] nameParts = toolName.split("[_\\-]");

            int totalScore = 0;

            for (int i = 0; i < terms.length; i++) {
                String term = terms[i].toLowerCase();
                int termScore = 0;

                // 1. 工具名完全匹配
                if (toolName.equals(term)) {
                    termScore = Math.max(termScore, SCORE_NAME_EXACT);
                }

                // 2. 工具名某个部分完全匹配
                for (String part : nameParts) {
                    if (part.equals(term)) {
                        termScore = Math.max(termScore, SCORE_NAME_PART_EXACT);
                    }
                }

                // 3. 工具名包含 term 子串
                if (toolName.contains(term)) {
                    termScore = Math.max(termScore, SCORE_NAME_PARTIAL);
                }

                // 4. 工具名部分包含 term 子串
                for (String part : nameParts) {
                    if (part.contains(term)) {
                        termScore = Math.max(termScore, SCORE_NAME_PART_PARTIAL);
                    }
                }

                // 5. searchHint 包含 term
                if (!searchHint.isEmpty() && searchHint.contains(term)) {
                    termScore = Math.max(termScore, SCORE_SEARCH_HINT);
                }

                // 6. 描述中包含 term（词边界匹配）
                if (termPatterns[i].matcher(description).find()) {
                    termScore = Math.max(termScore, SCORE_DESCRIPTION);
                }

                totalScore += termScore;
            }

            if (totalScore > 0) {
                scored.add(new ScoredTool(meta.name(), totalScore));
            }
        }

        // 按分数降序排列，取前 maxResults 个
        return scored.stream()
                .sorted(Comparator.comparingInt(ScoredTool::score).reversed())
                .limit(maxResults)
                .map(ScoredTool::name)
                .toList();
    }

    /**
     * 分词：按空格分割，去掉空串，全部小写。
     */
    private String[] tokenizeQuery(String query) {
        return query.toLowerCase().trim().split("\\s+");
    }

    // ================================================================
    //  辅助方法
    // ================================================================

    private int parseMaxResults(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_MAX_RESULTS;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : DEFAULT_MAX_RESULTS;
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_RESULTS;
        }
    }

    /**
     * 构建工具描述（包含延迟工具列表摘要）。
     * 对应 TS getPrompt()。
     */
    private String buildDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append("Search for available tools by keyword or select specific tools by name. ");
        sb.append("Use this to discover tools whose schemas are not loaded in the current context.\n\n");
        sb.append("Two modes:\n");
        sb.append("1. Direct selection: query='select:tool_name' or 'select:name1,name2'\n");
        sb.append("2. Keyword search: query='search terms' (fuzzy matching on tool names and descriptions)\n\n");

        // 列出延迟工具名称摘要
        List<String> deferredNames = allTools.stream()
                .filter(ToolSearchUtils::isDeferredTool)
                .map(t -> t.metadata().name())
                .toList();

        if (!deferredNames.isEmpty()) {
            sb.append("Available deferred tools (").append(deferredNames.size()).append("):\n");
            for (String name : deferredNames) {
                sb.append("- ").append(name).append("\n");
            }
        }

        return sb.toString().trim();
    }
}
