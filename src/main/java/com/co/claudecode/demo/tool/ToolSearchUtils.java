package com.co.claudecode.demo.tool;

import com.co.claudecode.demo.message.ContentBlock;
import com.co.claudecode.demo.message.ConversationMessage;
import com.co.claudecode.demo.message.ToolReferenceBlock;
import com.co.claudecode.demo.message.ToolResultBlock;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具延迟加载（Tool Deferred Loading）核心工具类。
 * <p>
 * 对应 TS {@code utils/toolSearch.ts} 中的核心函数。
 * <p>
 * 延迟加载机制：当工具数量过多时，不将所有工具 schema 发送给 API，
 * 而是只发送已被 ToolSearchTool 发现的工具。模型需要使用未发送 schema 的工具时，
 * 先调用 ToolSearchTool 进行发现。
 */
public final class ToolSearchUtils {

    /** ToolSearch 工具名常量（对应 TS TOOL_SEARCH_TOOL_NAME）。 */
    public static final String TOOL_SEARCH_TOOL_NAME = "ToolSearch";

    /**
     * 工具搜索模式（对应 TS ToolSearchMode）。
     * <ul>
     *   <li>{@code TST} — 始终启用延迟加载</li>
     *   <li>{@code TST_AUTO} — 当延迟工具 token 总量超过阈值时自动启用</li>
     *   <li>{@code STANDARD} — 不使用延迟加载（全量发送）</li>
     * </ul>
     */
    public enum ToolSearchMode {
        TST,
        TST_AUTO,
        STANDARD
    }

    /**
     * 自动模式阈值：延迟工具总字符数占上下文窗口 token 数的百分比。
     * 对应 TS DEFAULT_AUTO_THRESHOLD_PERCENTAGE = 0.1。
     */
    private static final double AUTO_THRESHOLD_PERCENTAGE = 0.10;

    /** 字符到 token 的近似转换比率（对应 TS fallback: 2.5 chars/token）。 */
    private static final double CHARS_PER_TOKEN = 2.5;

    /** 默认上下文窗口大小（token）。 */
    private static final int DEFAULT_CONTEXT_WINDOW = 200_000;

    private ToolSearchUtils() {
    }

    // ================================================================
    //  核心判定函数
    // ================================================================

    /**
     * 判断工具是否应被延迟加载。
     * <p>
     * 对应 TS {@code isDeferredTool(tool)} 的判定逻辑：
     * <ol>
     *   <li>{@code alwaysLoad=true} → 不延迟（最高优先级）</li>
     *   <li>{@code isMcp=true} → 延迟（MCP 工具默认延迟）</li>
     *   <li>ToolSearch 本身 → 不延迟</li>
     *   <li>{@code shouldDefer=true} → 延迟</li>
     *   <li>其他 → 不延迟</li>
     * </ol>
     *
     * @param tool 要判定的工具
     * @return true 表示应被延迟加载
     */
    public static boolean isDeferredTool(Tool tool) {
        ToolMetadata meta = tool.metadata();

        // alwaysLoad 最高优先级：显式选择不延迟
        if (meta.alwaysLoad()) {
            return false;
        }

        // MCP 工具默认延迟
        if (meta.isMcp()) {
            return true;
        }

        // ToolSearch 本身永不延迟
        if (TOOL_SEARCH_TOOL_NAME.equals(meta.name())) {
            return false;
        }

        // 显式标记为应延迟
        return meta.shouldDefer();
    }

    /**
     * 从环境变量解析工具搜索模式。
     * <p>
     * 对应 TS {@code getToolSearchMode()}：
     * 读取 {@code ENABLE_TOOL_SEARCH} 环境变量，
     * "tst" → TST, "tst-auto" → TST_AUTO, 其他 → STANDARD。
     */
    public static ToolSearchMode getToolSearchMode() {
        String envValue = System.getenv("ENABLE_TOOL_SEARCH");
        if (envValue == null || envValue.isBlank()) {
            return ToolSearchMode.STANDARD;
        }
        return switch (envValue.trim().toLowerCase()) {
            case "tst", "true" -> ToolSearchMode.TST;
            case "tst-auto", "auto" -> ToolSearchMode.TST_AUTO;
            case "false", "standard" -> ToolSearchMode.STANDARD;
            default -> ToolSearchMode.STANDARD;
        };
    }

    /**
     * 快速检查工具搜索是否可能启用（不含阈值检查）。
     * <p>
     * 对应 TS {@code isToolSearchEnabledOptimistic()}。
     */
    public static boolean isToolSearchEnabledOptimistic() {
        return getToolSearchMode() != ToolSearchMode.STANDARD;
    }

    /**
     * 完整检查工具搜索是否启用（含阈值检查）。
     * <p>
     * 对应 TS {@code isToolSearchEnabled(model, tools, ...)}：
     * <ul>
     *   <li>STANDARD 模式 → false</li>
     *   <li>TST 模式 → true（始终启用）</li>
     *   <li>TST_AUTO 模式 → 当延迟工具占比超过阈值时启用</li>
     * </ul>
     *
     * @param tools 全量工具集合
     * @return true 表示应启用工具搜索
     */
    public static boolean isToolSearchEnabled(Collection<Tool> tools) {
        ToolSearchMode mode = getToolSearchMode();
        if (mode == ToolSearchMode.STANDARD) {
            return false;
        }
        if (mode == ToolSearchMode.TST) {
            return true;
        }

        // TST_AUTO：检查阈值
        return checkAutoThreshold(tools);
    }

    /**
     * 自动阈值检查：估算延迟工具的 schema 总 token 数是否超过上下文窗口的一定比例。
     * <p>
     * 对应 TS {@code checkAutoThreshold(tools, contextWindow)}。
     * 使用字符数 / CHARS_PER_TOKEN 近似估算 token 数。
     */
    static boolean checkAutoThreshold(Collection<Tool> tools) {
        long totalDeferredChars = 0;
        for (Tool tool : tools) {
            if (isDeferredTool(tool)) {
                totalDeferredChars += estimateToolSchemaChars(tool);
            }
        }
        double estimatedTokens = totalDeferredChars / CHARS_PER_TOKEN;
        double threshold = DEFAULT_CONTEXT_WINDOW * AUTO_THRESHOLD_PERCENTAGE;
        return estimatedTokens >= threshold;
    }

    /**
     * 粗略估算工具 schema 的字符数。
     */
    private static long estimateToolSchemaChars(Tool tool) {
        ToolMetadata meta = tool.metadata();
        long chars = 0;
        chars += meta.name().length();
        chars += meta.description().length();
        for (ToolMetadata.ParamInfo param : meta.params()) {
            chars += param.name().length();
            chars += param.description().length();
        }
        return chars;
    }

    // ================================================================
    //  消息历史扫描
    // ================================================================

    /**
     * 从消息历史中提取所有已发现的工具名集合。
     * <p>
     * 对应 TS {@code extractDiscoveredToolNames(messages)}。
     * 扫描 user 消息中的 {@link ToolReferenceBlock}，收集 toolName。
     * 同时扫描 SYSTEM 消息中的 compact_boundary 携带的 preCompactDiscoveredTools。
     *
     * @param messages 完整会话消息列表
     * @return 已被 ToolSearchTool 发现的工具名集合
     */
    public static Set<String> extractDiscoveredToolNames(List<ConversationMessage> messages) {
        Set<String> discovered = new HashSet<>();

        for (ConversationMessage msg : messages) {
            // 扫描 user 消息中的 ToolReferenceBlock（ToolSearchTool 返回的 tool_result 内容）
            for (ContentBlock block : msg.blocks()) {
                if (block instanceof ToolReferenceBlock ref) {
                    if (!ref.toolName().isEmpty()) {
                        discovered.add(ref.toolName());
                    }
                }
                // 也扫描 ToolResultBlock 的内容中是否有 tool_reference 标记
                // （某些实现中 tool_reference 内嵌在 tool_result content 文本里）
                if (block instanceof ToolResultBlock tr && !tr.error()) {
                    extractToolReferencesFromText(tr.content(), discovered);
                }
            }
        }

        return discovered;
    }

    /**
     * 从 ToolResult 内容文本中解析 tool_reference 标记。
     * ToolSearchTool 返回格式：{@code [tool_reference] toolName}（每行一个）。
     */
    private static void extractToolReferencesFromText(String content, Set<String> discovered) {
        if (content == null || content.isEmpty()) return;
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[tool_reference] ")) {
                String toolName = trimmed.substring("[tool_reference] ".length()).trim();
                if (!toolName.isEmpty()) {
                    discovered.add(toolName);
                }
            }
        }
    }

    /**
     * 检查 ToolSearch 工具是否在工具集合中可用。
     */
    public static boolean isToolSearchToolAvailable(Collection<Tool> tools) {
        return tools.stream().anyMatch(t -> TOOL_SEARCH_TOOL_NAME.equals(t.metadata().name()));
    }

    // ================================================================
    //  Schema 未发送提示
    // ================================================================

    /**
     * 当模型尝试使用一个延迟工具但其 schema 未被发送时，构建提示消息。
     * <p>
     * 对应 TS {@code buildSchemaNotSentHint(tool, messages, tools)}。
     * 告知模型需要先调用 ToolSearchTool 来发现该工具。
     *
     * @param tool     被调用的工具
     * @param messages 当前会话消息列表
     * @param tools    全量工具集合
     * @return 提示消息字符串，如果无需提示则返回 null
     */
    public static String buildSchemaNotSentHint(Tool tool, List<ConversationMessage> messages,
                                                 Collection<Tool> tools) {
        // 如果工具搜索未启用，不提示
        if (!isToolSearchEnabledOptimistic()) {
            return null;
        }

        // 如果 ToolSearch 工具不可用，不提示
        if (!isToolSearchToolAvailable(tools)) {
            return null;
        }

        // 如果该工具不是延迟工具，不提示
        if (!isDeferredTool(tool)) {
            return null;
        }

        // 如果该工具已被发现，不提示
        Set<String> discovered = extractDiscoveredToolNames(messages);
        if (discovered.contains(tool.metadata().name())) {
            return null;
        }

        // 构建提示
        return "\n\nThis tool's schema was not sent to reduce prompt size. "
                + "To use this tool, first call " + TOOL_SEARCH_TOOL_NAME
                + " with query \"select:" + tool.metadata().name() + "\", then retry your request.";
    }

    // ================================================================
    //  工具过滤
    // ================================================================

    /**
     * 过滤工具列表，决定哪些工具的 schema 应该发送给 API。
     * <p>
     * 对应 TS {@code claude.ts} 中 queryModel 的过滤逻辑：
     * <ul>
     *   <li>非延迟工具 → 始终包含</li>
     *   <li>ToolSearch 本身 → 始终包含</li>
     *   <li>已被发现的延迟工具 → 包含</li>
     *   <li>未被发现的延迟工具 → 不包含（延迟）</li>
     * </ul>
     *
     * @param allTools 全量工具集合
     * @param messages 当前会话消息列表
     * @return 应发送 schema 的工具集合
     */
    public static List<Tool> filterToolsForApi(Collection<Tool> allTools,
                                                List<ConversationMessage> messages) {
        if (!isToolSearchEnabled(allTools)) {
            // 工具搜索未启用时，排除 ToolSearch 本身，发送所有其他工具
            return allTools.stream()
                    .filter(t -> !TOOL_SEARCH_TOOL_NAME.equals(t.metadata().name()))
                    .toList();
        }

        // 收集已发现的工具名
        Set<String> discoveredNames = extractDiscoveredToolNames(messages);

        // 收集延迟工具名
        Set<String> deferredNames = new HashSet<>();
        for (Tool tool : allTools) {
            if (isDeferredTool(tool)) {
                deferredNames.add(tool.metadata().name());
            }
        }

        return allTools.stream()
                .filter(tool -> {
                    String name = tool.metadata().name();
                    // 非延迟工具 → 总是包含
                    if (!deferredNames.contains(name)) {
                        return true;
                    }
                    // ToolSearch 本身 → 总是包含
                    if (TOOL_SEARCH_TOOL_NAME.equals(name)) {
                        return true;
                    }
                    // 已发现的延迟工具 → 包含
                    return discoveredNames.contains(name);
                })
                .toList();
    }

    /**
     * 判断工具是否会被延迟加载（用于构建 LlmRequest.ToolSchema 时设置 deferLoading 标志）。
     * <p>
     * 对应 TS {@code willDefer(t)} 函数。
     *
     * @param tool     工具
     * @param allTools 全量工具集合
     * @return true 表示该工具的 schema 应标记 deferLoading=true
     */
    public static boolean willDeferLoading(Tool tool, Collection<Tool> allTools) {
        return isToolSearchEnabled(allTools) && isDeferredTool(tool);
    }
}
