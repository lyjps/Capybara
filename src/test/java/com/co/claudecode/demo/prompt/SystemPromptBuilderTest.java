package com.co.claudecode.demo.prompt;

import com.co.claudecode.demo.agent.AgentDefinition;
import com.co.claudecode.demo.mcp.client.McpConnectionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SystemPromptBuilder 单元测试。
 * <p>
 * 验证美团生活场景 Agent「小团」的各段内容、组装逻辑、动态段生成。
 */
class SystemPromptBuilderTest {

    @TempDir
    Path tempDir;

    // ---- 静态段内容验证 ----

    @Test
    void roleAndGuidelines_containsAgentIdentity() {
        String role = SystemPromptSections.ROLE_AND_GUIDELINES;
        assertTrue(role.contains("美团Agent「小团」"),
                "ROLE_AND_GUIDELINES should define agent as 小团");
        assertTrue(role.contains("全生活场景"),
                "ROLE_AND_GUIDELINES should mention life-scenario scope");
    }

    @Test
    void roleAndGuidelines_containsPersistencePolicy() {
        String role = SystemPromptSections.ROLE_AND_GUIDELINES;
        assertTrue(role.contains("坚持满足用户需求"),
                "Should contain user satisfaction persistence policy");
        assertTrue(role.contains("想尽至少三种不同方式"),
                "Should require at least three different approaches");
    }

    @Test
    void roleAndGuidelines_containsToolUsagePrinciples() {
        String role = SystemPromptSections.ROLE_AND_GUIDELINES;
        assertTrue(role.contains("工具使用原则"),
                "Should contain tool usage principles header");
        assertTrue(role.contains("不要为了用工具而用工具"),
                "Should warn against unnecessary tool use");
        assertTrue(role.contains("工具要串联"),
                "Should encourage tool chaining");
        assertTrue(role.contains("结果不好就换词或换工具"),
                "Should encourage retrying with different keywords");
    }

    @Test
    void roleAndGuidelines_containsSearchCompletenessPrinciples() {
        String role = SystemPromptSections.ROLE_AND_GUIDELINES;
        assertTrue(role.contains("搜索结果的完整性原则"),
                "Should contain search completeness header");
        assertTrue(role.contains("原则一"),
                "Should contain principle 1");
        assertTrue(role.contains("原则二"),
                "Should contain principle 2");
        assertTrue(role.contains("原则三"),
                "Should contain principle 3");
    }

    @Test
    void roleAndGuidelines_containsSearchPrinciple1_mixedResults() {
        String role = SystemPromptSections.ROLE_AND_GUIDELINES;
        assertTrue(role.contains("不要只看"),
                "Principle 1 should warn against type-only filtering");
        assertTrue(role.contains("逐条读完每一个返回结果"),
                "Principle 1 should require reading every result");
    }

    @Test
    void roleAndGuidelines_containsSearchPrinciple2_retryOnEmpty() {
        String role = SystemPromptSections.ROLE_AND_GUIDELINES;
        assertTrue(role.contains("返回空不等于没有"),
                "Principle 2 should warn against empty = none conclusion");
        assertTrue(role.contains("梯度扩大范围重试"),
                "Principle 2 should suggest expanding search range");
    }

    @Test
    void roleAndGuidelines_containsSearchPrinciple3_exhaustiveVerification() {
        String role = SystemPromptSections.ROLE_AND_GUIDELINES;
        assertTrue(role.contains("每条线索都要验证完才能回复"),
                "Principle 3 should require verifying every lead");
        assertTrue(role.contains("全部核查完毕后才能回复用户"),
                "Principle 3 should require complete verification before reply");
    }

    @Test
    void systemSection_containsMarkdownRule() {
        String system = SystemPromptSections.SYSTEM;
        assertTrue(system.contains("Markdown"),
                "SYSTEM should mention Markdown formatting");
    }

    @Test
    void systemSection_containsParallelToolRule() {
        String system = SystemPromptSections.SYSTEM;
        assertTrue(system.contains("并行调用"),
                "SYSTEM should mention parallel tool calls");
    }

    @Test
    void systemSection_containsInjectionDefense() {
        String system = SystemPromptSections.SYSTEM;
        assertTrue(system.contains("外部数据注入"),
                "SYSTEM should warn about external data injection");
    }

    @Test
    void systemSection_containsAutoCompress() {
        String system = SystemPromptSections.SYSTEM;
        assertTrue(system.contains("自动压缩历史消息"),
                "SYSTEM should mention auto-compress");
    }

    @Test
    void outputStyle_containsConciseness() {
        String style = SystemPromptSections.OUTPUT_STYLE;
        assertTrue(style.contains("简洁"),
                "OUTPUT_STYLE should require conciseness");
        assertTrue(style.contains("直奔主题"),
                "OUTPUT_STYLE should require directness");
    }

    @Test
    void outputStyle_containsRecommendationFormat() {
        String style = SystemPromptSections.OUTPUT_STYLE;
        assertTrue(style.contains("名称、评分、价格、地址"),
                "OUTPUT_STYLE should specify recommendation info fields");
    }

    @Test
    void outputStyle_containsListOrTableFormat() {
        String style = SystemPromptSections.OUTPUT_STYLE;
        assertTrue(style.contains("列表或表格"),
                "OUTPUT_STYLE should suggest list/table format");
    }

    // ---- 动态段：availableToolsSection ----

    @Test
    void availableToolsSection_withMeituanSearch_containsSearchDesc() {
        String section = SystemPromptBuilder.availableToolsSection(
                Set.of("meituan_search_mix", "content_search"));
        assertTrue(section.contains("美团综合搜索"),
                "Should contain 美团综合搜索 description");
        assertTrue(section.contains("内容搜索"),
                "Should contain 内容搜索 description");
    }

    @Test
    void availableToolsSection_withMapTools_containsMapDesc() {
        String section = SystemPromptBuilder.availableToolsSection(
                Set.of("mt_map_geo", "mt_map_nearby", "mt_map_direction"));
        assertTrue(section.contains("地理编码"),
                "Should contain geo description");
        assertTrue(section.contains("周边搜索"),
                "Should contain nearby search description");
        assertTrue(section.contains("路径规划"),
                "Should contain direction description");
    }

    @Test
    void availableToolsSection_withMcpTools_containsMcpHint() {
        String section = SystemPromptBuilder.availableToolsSection(
                Set.of("mcp__amap-maps__geo"));
        assertTrue(section.contains("外部地图服务"),
                "Should contain MCP map service hint");
    }

    @Test
    void availableToolsSection_withAgent_containsAgentDesc() {
        String section = SystemPromptBuilder.availableToolsSection(
                Set.of("agent"));
        assertTrue(section.contains("子代理"),
                "Should contain agent description");
    }

    @Test
    void availableToolsSection_withTaskTools_containsTaskDesc() {
        String section = SystemPromptBuilder.availableToolsSection(
                Set.of("task_create"));
        assertTrue(section.contains("任务管理"),
                "Should contain task management description");
    }

    @Test
    void availableToolsSection_withDetailAndPoiSearch_containsDesc() {
        String section = SystemPromptBuilder.availableToolsSection(
                Set.of("id_detail_pro", "meituan_search_poi"));
        assertTrue(section.contains("详情查询"),
                "Should contain detail query description");
        assertTrue(section.contains("店铺内搜索"),
                "Should contain POI search description");
    }

    @Test
    void availableToolsSection_withAllMapTools_containsAllDesc() {
        String section = SystemPromptBuilder.availableToolsSection(
                Set.of("mt_map_geo", "mt_map_regeo", "mt_map_text_search",
                        "mt_map_nearby", "mt_map_direction", "mt_map_distance",
                        "mt_map_iplocate", "mt_map_poiprovide"));
        assertTrue(section.contains("地理编码"), "Should contain geo");
        assertTrue(section.contains("逆地理编码"), "Should contain regeo");
        assertTrue(section.contains("地图关键词搜索"), "Should contain text search");
        assertTrue(section.contains("周边搜索"), "Should contain nearby");
        assertTrue(section.contains("路径规划"), "Should contain direction");
        assertTrue(section.contains("距离测量"), "Should contain distance");
        assertTrue(section.contains("IP 定位"), "Should contain ip locate");
        assertTrue(section.contains("POI 详情"), "Should contain poi provide");
    }

    @Test
    void availableToolsSection_withEmptyTools_onlyHeader() {
        String section = SystemPromptBuilder.availableToolsSection(Set.of());
        assertTrue(section.contains("可用工具"),
                "Should have header even with no tools");
        assertFalse(section.contains("美团综合搜索"),
                "Should not list tools when none enabled");
    }

    // ---- 动态段：languageSection ----

    @Test
    void languageSection_withChinese_containsDirective() {
        String lang = SystemPromptBuilder.languageSection("Chinese");
        assertTrue(lang.contains("请始终使用Chinese回复用户"),
                "Should contain Chinese language directive");
        assertTrue(lang.contains("# 语言"),
                "Should have language header");
    }

    @Test
    void languageSection_withNull_returnsEmpty() {
        assertEquals("", SystemPromptBuilder.languageSection(null));
    }

    @Test
    void languageSection_withBlank_returnsEmpty() {
        assertEquals("", SystemPromptBuilder.languageSection("  "));
    }

    // ---- 动态段：memorySection ----

    @Test
    void memorySection_withClaudeMd_containsContent() throws IOException {
        Files.writeString(tempDir.resolve("CLAUDE.md"), "# 测试项目\n使用 mvn 构建。");
        String memory = SystemPromptBuilder.memorySection(tempDir);
        assertTrue(memory.contains("# 测试项目"),
                "Should contain CLAUDE.md content");
        assertTrue(memory.contains("使用 mvn 构建"),
                "Should contain CLAUDE.md content");
        assertTrue(memory.contains("CLAUDE.md"),
                "Should reference CLAUDE.md");
    }

    @Test
    void memorySection_withoutClaudeMd_returnsEmpty() {
        String memory = SystemPromptBuilder.memorySection(tempDir);
        assertEquals("", memory);
    }

    @Test
    void memorySection_withNull_returnsEmpty() {
        assertEquals("", SystemPromptBuilder.memorySection(null));
    }

    @Test
    void memorySection_withEmptyClaudeMd_returnsEmpty() throws IOException {
        Files.writeString(tempDir.resolve("CLAUDE.md"), "   ");
        String memory = SystemPromptBuilder.memorySection(tempDir);
        assertEquals("", memory);
    }

    // ---- 动态段：mcpInstructionsSection ----

    @Test
    void mcpInstructionsSection_withNull_returnsEmpty() {
        assertEquals("", SystemPromptBuilder.mcpInstructionsSection(null));
    }

    @Test
    void mcpInstructionsSection_withNoConnections_returnsEmpty() {
        McpConnectionManager mgr = new McpConnectionManager();
        assertEquals("", SystemPromptBuilder.mcpInstructionsSection(mgr));
        mgr.close();
    }

    // ---- 组装：buildMainPrompt ----

    @Test
    void buildMainPrompt_containsRoleAndGuidelines() {
        String prompt = SystemPromptBuilder.buildMainPrompt(
                tempDir, "test-model", Set.of("meituan_search_mix"), null, "Chinese");
        assertTrue(prompt.contains("美团Agent「小团」"),
                "Missing agent identity in main prompt");
        assertTrue(prompt.contains("坚持满足用户需求"),
                "Missing persistence policy in main prompt");
    }

    @Test
    void buildMainPrompt_containsSystemSection() {
        String prompt = SystemPromptBuilder.buildMainPrompt(
                tempDir, "test-model", Set.of("meituan_search_mix"), null, null);
        assertTrue(prompt.contains("Markdown"),
                "Missing SYSTEM section");
        assertTrue(prompt.contains("并行调用"),
                "Missing parallel tool rule");
    }

    @Test
    void buildMainPrompt_containsAvailableTools() {
        String prompt = SystemPromptBuilder.buildMainPrompt(
                tempDir, "test-model",
                Set.of("meituan_search_mix", "content_search", "mt_map_nearby"),
                null, null);
        assertTrue(prompt.contains("可用工具"),
                "Missing available tools header");
        assertTrue(prompt.contains("美团综合搜索"),
                "Missing meituan search tool");
        assertTrue(prompt.contains("周边搜索"),
                "Missing nearby search tool");
    }

    @Test
    void buildMainPrompt_containsOutputStyle() {
        String prompt = SystemPromptBuilder.buildMainPrompt(
                tempDir, "test-model", Set.of(), null, null);
        assertTrue(prompt.contains("简洁"),
                "Missing output style");
        assertTrue(prompt.contains("名称、评分、价格、地址"),
                "Missing recommendation format guidance");
    }

    @Test
    void buildMainPrompt_containsEnvironmentInfo() {
        String prompt = SystemPromptBuilder.buildMainPrompt(
                tempDir, "test-model", Set.of(), null, null);
        assertTrue(prompt.contains("# Environment"), "Missing env header");
        assertTrue(prompt.contains("Primary working directory"), "Missing cwd");
        assertTrue(prompt.contains("test-model"), "Missing model name");
        assertTrue(prompt.contains("Platform"), "Missing platform");
        assertTrue(prompt.contains("Java version"), "Missing java version");
    }

    @Test
    void buildMainPrompt_withLanguage_containsLanguageDirective() {
        String prompt = SystemPromptBuilder.buildMainPrompt(
                tempDir, "test-model", Set.of(), null, "Chinese");
        assertTrue(prompt.contains("请始终使用Chinese回复用户"),
                "Missing Chinese language directive");
    }

    @Test
    void buildMainPrompt_withNullLanguage_noLanguageSection() {
        String prompt = SystemPromptBuilder.buildMainPrompt(
                tempDir, "test-model", Set.of(), null, null);
        assertFalse(prompt.contains("# 语言"),
                "Should not contain language section when null");
    }

    @Test
    void buildMainPrompt_containsToolResultSummary() {
        String prompt = SystemPromptBuilder.buildMainPrompt(
                tempDir, "test-model", Set.of(), null, null);
        assertTrue(prompt.contains("商家名称、地址、评分、价格"),
                "Should contain tool result summary about merchant info");
    }

    @Test
    void buildMainPrompt_withClaudeMd_containsMemory() throws IOException {
        Files.writeString(tempDir.resolve("CLAUDE.md"), "构建命令: mvn package");
        String prompt = SystemPromptBuilder.buildMainPrompt(
                tempDir, "test-model", Set.of(), null, null);
        assertTrue(prompt.contains("构建命令: mvn package"),
                "Main prompt should include CLAUDE.md content");
    }

    // ---- 组装：buildDemoPrompt ----

    @Test
    void buildDemoPrompt_containsEssentialSections() {
        String demo = SystemPromptBuilder.buildDemoPrompt(tempDir, "test-model");
        assertTrue(demo.contains("美团Agent「小团」"),
                "Missing agent identity in demo");
        assertTrue(demo.contains("简洁"),
                "Missing OUTPUT_STYLE in demo");
        assertTrue(demo.contains("# Environment"),
                "Missing env info in demo");
    }

    @Test
    void buildDemoPrompt_shorterThanMain() {
        String demo = SystemPromptBuilder.buildDemoPrompt(tempDir, "test-model");
        String main = SystemPromptBuilder.buildMainPrompt(
                tempDir, "test-model",
                Set.of("meituan_search_mix", "content_search", "agent"),
                null, "Chinese");
        assertTrue(demo.length() < main.length(),
                "Demo prompt should be shorter than main prompt");
    }

    @Test
    void buildDemoPrompt_omitsSystemSection() {
        String demo = SystemPromptBuilder.buildDemoPrompt(tempDir, "test-model");
        assertFalse(demo.contains("# 系统规范"),
                "Demo should omit full SYSTEM section");
    }

    @Test
    void buildDemoPrompt_omitsAvailableTools() {
        String demo = SystemPromptBuilder.buildDemoPrompt(tempDir, "test-model");
        assertFalse(demo.contains("# 可用工具"),
                "Demo should omit available tools section");
    }

    @Test
    void buildDemoPrompt_withClaudeMd_containsMemory() throws IOException {
        Files.writeString(tempDir.resolve("CLAUDE.md"), "项目说明: 美团生活服务");
        String demo = SystemPromptBuilder.buildDemoPrompt(tempDir, "test-model");
        assertTrue(demo.contains("项目说明: 美团生活服务"),
                "Demo prompt should include CLAUDE.md content");
    }

    // ---- 组装：buildAgentPrompt ----

    @Test
    void buildAgentPrompt_containsDefinitionPrompt() {
        AgentDefinition agentDef = AgentDefinition.builtIn(
                "test-agent", "For testing",
                "你是一个测试代理。请查找商家信息。",
                null, java.util.List.of(), false, 12);

        String prompt = SystemPromptBuilder.buildAgentPrompt(agentDef, tempDir, null);
        assertTrue(prompt.contains("你是一个测试代理"),
                "Should contain agent definition's prompt");
    }

    @Test
    void buildAgentPrompt_withBlankPrompt_usesDefault() {
        AgentDefinition agentDef = AgentDefinition.builtIn(
                "empty-agent", "For testing",
                "   ", // blank
                null, java.util.List.of(), false, 12);

        String prompt = SystemPromptBuilder.buildAgentPrompt(agentDef, tempDir, null);
        assertTrue(prompt.contains("子代理"),
                "Should fall back to DEFAULT_AGENT_PROMPT");
    }

    @Test
    void buildAgentPrompt_withNullPrompt_usesDefault() {
        AgentDefinition agentDef = AgentDefinition.builtIn(
                "null-agent", "For testing",
                null,
                null, java.util.List.of(), false, 12);

        String prompt = SystemPromptBuilder.buildAgentPrompt(agentDef, tempDir, null);
        assertTrue(prompt.contains("子代理"),
                "Should fall back to DEFAULT_AGENT_PROMPT when null");
    }

    @Test
    void buildAgentPrompt_containsAgentNotes() {
        AgentDefinition agentDef = AgentDefinition.builtIn(
                "test-agent", "For testing", "做事情。",
                null, java.util.List.of(), false, 12);

        String prompt = SystemPromptBuilder.buildAgentPrompt(agentDef, tempDir, null);
        assertTrue(prompt.contains("绝对文件路径"),
                "Should contain agent notes about absolute paths");
        assertTrue(prompt.contains("不要使用 emoji"),
                "Should contain no-emoji rule");
    }

    @Test
    void buildAgentPrompt_containsEnvironment() {
        AgentDefinition agentDef = AgentDefinition.builtIn(
                "test-agent", "For testing", "做事情。",
                null, java.util.List.of(), false, 12);

        String prompt = SystemPromptBuilder.buildAgentPrompt(agentDef, tempDir, "model-x");
        assertTrue(prompt.contains("# Environment"),
                "Should contain environment info");
        assertTrue(prompt.contains("model-x"),
                "Should contain model name in env");
    }

    @Test
    void buildAgentPrompt_withClaudeMd_containsMemory() throws IOException {
        Files.writeString(tempDir.resolve("CLAUDE.md"), "测试项目规则");
        AgentDefinition agentDef = AgentDefinition.builtIn(
                "test-agent", "For testing", "做事情。",
                null, java.util.List.of(), false, 12);

        String prompt = SystemPromptBuilder.buildAgentPrompt(agentDef, tempDir, null);
        assertTrue(prompt.contains("测试项目规则"),
                "Agent prompt should include CLAUDE.md");
    }

    // ---- 常量验证 ----

    @Test
    void defaultAgentPrompt_isNotEmpty() {
        assertFalse(SystemPromptSections.DEFAULT_AGENT_PROMPT.isBlank());
        assertTrue(SystemPromptSections.DEFAULT_AGENT_PROMPT.contains("子代理"));
    }

    @Test
    void agentNotes_containsAbsolutePaths() {
        assertTrue(SystemPromptSections.AGENT_NOTES.contains("绝对文件路径"));
        assertTrue(SystemPromptSections.AGENT_NOTES.contains("不要使用 emoji"));
    }

    @Test
    void toolResultSummary_isNotEmpty() {
        assertFalse(SystemPromptSections.TOOL_RESULT_SUMMARY.isBlank());
        assertTrue(SystemPromptSections.TOOL_RESULT_SUMMARY.contains("商家名称"));
    }

    // ---- 段落组合顺序验证 ----

    @Test
    void buildMainPrompt_sectionsInCorrectOrder() {
        String prompt = SystemPromptBuilder.buildMainPrompt(
                tempDir, "test-model",
                Set.of("meituan_search_mix"),
                null, "Chinese");

        int roleIdx = prompt.indexOf("美团Agent「小团」");
        int systemIdx = prompt.indexOf("# 系统规范");
        int toolsIdx = prompt.indexOf("# 可用工具");
        int styleIdx = prompt.indexOf("# 输出风格");
        int envIdx = prompt.indexOf("# Environment");

        assertTrue(roleIdx < systemIdx,
                "ROLE_AND_GUIDELINES should come before SYSTEM");
        assertTrue(systemIdx < toolsIdx,
                "SYSTEM should come before available tools");
        assertTrue(toolsIdx < styleIdx,
                "Available tools should come before OUTPUT_STYLE");
        assertTrue(styleIdx < envIdx,
                "OUTPUT_STYLE should come before environment info");
    }
}
