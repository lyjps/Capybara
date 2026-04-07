package com.co.claudecode.demo.prompt;

import com.co.claudecode.demo.agent.AgentDefinition;
import com.co.claudecode.demo.mcp.McpServerConnection;
import com.co.claudecode.demo.mcp.client.McpConnectionManager;
import com.co.claudecode.demo.skill.SkillRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 模块化 system prompt 构建器 — 面向美团生活场景 Agent「小团」。
 * <p>
 * 组装逻辑：固定段（角色定义 + 重要提醒 + 系统规范 + 输出风格）
 * + 动态段（环境信息 + 语言 + CLAUDE.md + MCP 指令 + 工具结果提醒）。
 * <p>
 * 三个组装方法：
 * <ul>
 *   <li>{@link #buildMainPrompt} — 交互式 REPL（全量 prompt）</li>
 *   <li>{@link #buildDemoPrompt} — 单任务模式（精简版）</li>
 *   <li>{@link #buildAgentPrompt} — 子 Agent 执行</li>
 * </ul>
 */
public final class SystemPromptBuilder {

    private SystemPromptBuilder() {
    }

    // ================================================================
    //  动态段：可用工具概览
    // ================================================================

    /**
     * 根据实际注册的工具集，生成工具概览段落。
     * <p>
     * 面向生活场景，重点描述搜索/地图/内容类工具的串联用法。
     *
     * @param enabledToolNames 所有已注册工具名
     * @return 工具概览段落
     */
    public static String availableToolsSection(Set<String> enabledToolNames) {
        StringBuilder sb = new StringBuilder("# 可用工具\n以下工具可协助你完成任务：\n");

        // 美团搜索类
        if (enabledToolNames.contains("meituan_search_mix")) {
            sb.append(" - **美团综合搜索**（meituan_search_mix）：搜索商家、商品、服务，确认供给和价格\n");
        }
        if (enabledToolNames.contains("content_search")) {
            sb.append(" - **内容搜索**（content_search）：搜索网友评论、攻略、口碑，了解真实体验\n");
        }
        if (enabledToolNames.contains("id_detail_pro")) {
            sb.append(" - **详情查询**（id_detail_pro）：查询特定商家/商品的详细信息\n");
        }
        if (enabledToolNames.contains("meituan_search_poi")) {
            sb.append(" - **店铺内搜索**（meituan_search_poi）：在指定店铺内搜索商品\n");
        }

        // 美团地图类
        if (enabledToolNames.contains("mt_map_geo")) {
            sb.append(" - **地理编码**（mt_map_geo）：地址转经纬度\n");
        }
        if (enabledToolNames.contains("mt_map_regeo")) {
            sb.append(" - **逆地理编码**（mt_map_regeo）：经纬度转地址\n");
        }
        if (enabledToolNames.contains("mt_map_text_search")) {
            sb.append(" - **地图关键词搜索**（mt_map_text_search）：按关键词搜索地点\n");
        }
        if (enabledToolNames.contains("mt_map_nearby")) {
            sb.append(" - **周边搜索**（mt_map_nearby）：搜索附近的场所和设施\n");
        }
        if (enabledToolNames.contains("mt_map_direction")) {
            sb.append(" - **路径规划**（mt_map_direction）：规划出行路线\n");
        }
        if (enabledToolNames.contains("mt_map_distance")) {
            sb.append(" - **距离测量**（mt_map_distance）：计算两点间距离\n");
        }
        if (enabledToolNames.contains("mt_map_iplocate")) {
            sb.append(" - **IP 定位**（mt_map_iplocate）：根据 IP 获取大致位置\n");
        }
        if (enabledToolNames.contains("mt_map_poiprovide")) {
            sb.append(" - **POI 详情**（mt_map_poiprovide）：查询 POI 详细信息\n");
        }

        // MCP 直通工具（如高德）
        boolean hasMcpTools = enabledToolNames.stream().anyMatch(n -> n.startsWith("mcp__"));
        if (hasMcpTools) {
            sb.append(" - **外部地图服务**（mcp__ 前缀工具）：高德等第三方地图服务\n");
        }

        // Agent / Task 工具（如果有）
        if (enabledToolNames.contains("agent")) {
            sb.append(" - **子代理**（agent）：将复杂任务拆分给子代理并行执行\n");
        }
        if (enabledToolNames.contains("task_create")) {
            sb.append(" - **任务管理**（task_create/get/list/update）：跟踪多步骤任务进度\n");
        }

        // Skill 工具
        if (enabledToolNames.contains("Skill")) {
            sb.append(" - **技能调用**（Skill）：调用已配置的 skill，执行专业化任务\n");
        }

        return sb.toString().stripTrailing();
    }

    // ================================================================
    //  动态段：Skill 列表
    // ================================================================

    /**
     * 根据 SkillRegistry 生成可用 skill 列表段落，注入 system prompt。
     *
     * @param skillRegistry skill 注册表（nullable）
     * @return skill 列表段，或空字符串
     */
    public static String skillListingSection(SkillRegistry skillRegistry) {
        if (skillRegistry == null || !skillRegistry.hasSkills()) {
            return "";
        }
        return "# 可用 Skills\n" + skillRegistry.buildSkillListing();
    }

    // ================================================================
    //  动态段：语言
    // ================================================================

    /**
     * 语言偏好段。
     *
     * @param language 偏好语言（如 "Chinese"），nullable
     * @return 语言段，或空字符串
     */
    public static String languageSection(String language) {
        if (language == null || language.isBlank()) {
            return "";
        }
        return "# 语言\n请始终使用" + language + "回复用户。专业术语和品牌名可保留原文。";
    }

    // ================================================================
    //  动态段：Memory (CLAUDE.md)
    // ================================================================

    /**
     * 从工作区根目录加载 CLAUDE.md 作为项目记忆。
     *
     * @param workspaceRoot 工作区根目录（nullable）
     * @return 记忆段，或空字符串
     */
    public static String memorySection(Path workspaceRoot) {
        if (workspaceRoot == null) {
            return "";
        }
        Path claudeMd = workspaceRoot.resolve("CLAUDE.md");
        if (!Files.isRegularFile(claudeMd)) {
            return "";
        }
        try {
            String content = Files.readString(claudeMd);
            if (content.isBlank()) {
                return "";
            }
            return "# Project Memory (CLAUDE.md)\n\n" + content.strip();
        } catch (IOException e) {
            return "";
        }
    }

    // ================================================================
    //  动态段：MCP 指令
    // ================================================================

    /**
     * 收集已连接 MCP 服务器提供的使用指令。
     *
     * @param mcpManager MCP 连接管理器（nullable）
     * @return MCP 指令段，或空字符串
     */
    public static String mcpInstructionsSection(McpConnectionManager mcpManager) {
        if (mcpManager == null) {
            return "";
        }
        List<String> blocks = new ArrayList<>();
        for (McpServerConnection conn : mcpManager.allConnections()) {
            if (conn.isConnected()
                    && conn.instructions() != null
                    && !conn.instructions().isBlank()) {
                blocks.add("## " + conn.name() + "\n" + conn.instructions().strip());
            }
        }
        if (blocks.isEmpty()) {
            return "";
        }
        return "# MCP 服务器指令\n\n"
                + "以下 MCP 服务器提供了工具使用指引：\n\n"
                + String.join("\n\n", blocks);
    }

    // ================================================================
    //  组装：主 Prompt（交互式 REPL）
    // ================================================================

    /**
     * 构建交互式模式的完整 system prompt（向后兼容版，无 Skill）。
     *
     * @param workspaceRoot    工作区目录
     * @param modelName        模型标识
     * @param enabledToolNames 所有已注册工具名
     * @param mcpManager       MCP 连接管理器（nullable）
     * @param language         偏好语言（nullable）
     * @return 完整 system prompt
     */
    public static String buildMainPrompt(Path workspaceRoot,
                                         String modelName,
                                         Set<String> enabledToolNames,
                                         McpConnectionManager mcpManager,
                                         String language) {
        return buildMainPrompt(workspaceRoot, modelName, enabledToolNames,
                mcpManager, language, null);
    }

    /**
     * 构建交互式模式的完整 system prompt。
     * <p>
     * 包含：角色定义 + 重要提醒 + 系统规范 + 可用工具 + Skill 列表 + 输出风格
     * + 环境信息 + 语言 + CLAUDE.md + MCP 指令 + 工具结果提醒。
     *
     * @param workspaceRoot    工作区目录
     * @param modelName        模型标识
     * @param enabledToolNames 所有已注册工具名
     * @param mcpManager       MCP 连接管理器（nullable）
     * @param language         偏好语言（nullable）
     * @param skillRegistry    Skill 注册表（nullable）
     * @return 完整 system prompt
     */
    public static String buildMainPrompt(Path workspaceRoot,
                                         String modelName,
                                         Set<String> enabledToolNames,
                                         McpConnectionManager mcpManager,
                                         String language,
                                         SkillRegistry skillRegistry) {
        List<String> sections = new ArrayList<>();

        // --- 固定段 ---
        sections.add(SystemPromptSections.ROLE_AND_GUIDELINES);
        sections.add(SystemPromptSections.SYSTEM);
        sections.add(availableToolsSection(enabledToolNames));

        // --- Skill 列表（工具描述之后、输出风格之前）---
        String skillListing = skillListingSection(skillRegistry);
        if (!skillListing.isEmpty()) {
            sections.add(skillListing);
        }

        sections.add(SystemPromptSections.OUTPUT_STYLE);

        // --- 动态段 ---
        sections.add(EnvironmentInfo.collect(workspaceRoot, modelName));

        String lang = languageSection(language);
        if (!lang.isEmpty()) {
            sections.add(lang);
        }

        String memory = memorySection(workspaceRoot);
        if (!memory.isEmpty()) {
            sections.add(memory);
        }

        String mcpInstr = mcpInstructionsSection(mcpManager);
        if (!mcpInstr.isEmpty()) {
            sections.add(mcpInstr);
        }

        sections.add(SystemPromptSections.TOOL_RESULT_SUMMARY);

        return String.join("\n\n", sections);
    }

    // ================================================================
    //  组装：Demo Prompt（单任务模式）
    // ================================================================

    /**
     * 构建单任务模式的精简 prompt。
     *
     * @param workspaceRoot 工作区目录
     * @param modelName     模型标识
     * @return 精简 system prompt
     */
    public static String buildDemoPrompt(Path workspaceRoot, String modelName) {
        List<String> sections = new ArrayList<>();
        sections.add(SystemPromptSections.ROLE_AND_GUIDELINES);
        sections.add(SystemPromptSections.OUTPUT_STYLE);
        sections.add(EnvironmentInfo.collect(workspaceRoot, modelName));

        String memory = memorySection(workspaceRoot);
        if (!memory.isEmpty()) {
            sections.add(memory);
        }

        return String.join("\n\n", sections);
    }

    // ================================================================
    //  组装：Agent Prompt（子 Agent）
    // ================================================================

    /**
     * 构建子 Agent 的 system prompt。
     *
     * @param agentDef      Agent 定义
     * @param workspaceRoot 工作区目录
     * @param modelName     模型标识（nullable）
     * @return 子 Agent system prompt
     */
    public static String buildAgentPrompt(AgentDefinition agentDef,
                                          Path workspaceRoot,
                                          String modelName) {
        List<String> sections = new ArrayList<>();

        // Agent 自身 prompt（兜底用 DEFAULT_AGENT_PROMPT）
        String agentPrompt = agentDef.systemPrompt();
        if (agentPrompt == null || agentPrompt.isBlank()) {
            sections.add(SystemPromptSections.DEFAULT_AGENT_PROMPT);
        } else {
            sections.add(agentPrompt);
        }

        // Agent 执行指引
        sections.add(SystemPromptSections.AGENT_NOTES);

        // 环境信息
        sections.add(EnvironmentInfo.collect(workspaceRoot, modelName));

        // CLAUDE.md
        String memory = memorySection(workspaceRoot);
        if (!memory.isEmpty()) {
            sections.add(memory);
        }

        return String.join("\n\n", sections);
    }
}
