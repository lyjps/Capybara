package com.co.claudecode.demo.skill;

import com.co.claudecode.demo.agent.AgentDefinition;
import com.co.claudecode.demo.agent.AgentRegistry;
import com.co.claudecode.demo.agent.AgentResult;
import com.co.claudecode.demo.agent.SubAgentRunner;
import com.co.claudecode.demo.tool.Tool;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolMetadata;
import com.co.claudecode.demo.tool.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Skill 工具 -- 供模型调用已加载的 skill。
 * <p>
 * 对应 TS 原版 {@code SkillTool} 的核心逻辑。
 * <p>
 * 两种执行模式：
 * <ul>
 *   <li><b>inline</b>：将 skill prompt 作为工具结果返回，模型在当前对话中继续执行</li>
 *   <li><b>fork</b>：启动子 Agent 执行 skill prompt，返回子 Agent 结果</li>
 * </ul>
 * <p>
 * 参数：
 * <ul>
 *   <li>{@code skill} (required) — skill 名称</li>
 *   <li>{@code args} (optional) — 传给 skill 的参数字符串</li>
 * </ul>
 */
public final class SkillTool implements Tool {

    private final SkillRegistry skillRegistry;
    private final SubAgentRunner subAgentRunner;
    private final AgentRegistry agentRegistry;

    /**
     * @param skillRegistry  skill 注册表
     * @param subAgentRunner 子 Agent 执行器（fork 模式需要，nullable — 仅 inline 可用）
     * @param agentRegistry  Agent 注册表（fork 模式需要，nullable）
     */
    public SkillTool(SkillRegistry skillRegistry,
                     SubAgentRunner subAgentRunner,
                     AgentRegistry agentRegistry) {
        this.skillRegistry = skillRegistry;
        this.subAgentRunner = subAgentRunner;
        this.agentRegistry = agentRegistry;
    }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                "Skill",
                "Execute a skill within the main conversation. "
                        + "Skills provide specialized capabilities and domain knowledge. "
                        + "Available skills: " + availableSkillNames(),
                true,   // readOnly — Skill 工具本身只读，内部工具控制读写
                true,   // concurrencySafe
                false,  // destructive
                ToolMetadata.PathDomain.NONE, null,
                List.of(
                        new ToolMetadata.ParamInfo("skill",
                                "The skill name to invoke", true),
                        new ToolMetadata.ParamInfo("args",
                                "Optional arguments for the skill", false)
                ));
    }

    @Override
    public void validate(Map<String, String> input) {
        if (input.get("skill") == null || input.get("skill").isBlank()) {
            throw new IllegalArgumentException("skill parameter is required");
        }
    }

    @Override
    public ToolResult execute(Map<String, String> input, ToolExecutionContext context) throws Exception {
        String skillName = input.get("skill");
        String args = input.getOrDefault("args", "");

        // 查找 skill
        SkillDefinition skill = skillRegistry.findByName(skillName);
        if (skill == null) {
            return new ToolResult(true,
                    "Unknown skill: " + skillName
                            + ". Available skills: " + availableSkillNames());
        }

        // 解析 prompt
        String resolvedPrompt = skill.resolvePrompt(args);

        // 根据执行模式分支
        if (skill.context() == SkillDefinition.ExecutionMode.FORK) {
            return executeFork(skill, resolvedPrompt, context);
        } else {
            return executeInline(skill, resolvedPrompt);
        }
    }

    // ================================================================
    //  Inline Execution
    // ================================================================

    /**
     * Inline 模式：将 skill prompt 作为工具结果返回。
     * <p>
     * 模型会在当前对话上下文中处理这个 prompt，就像它收到了一条指令。
     */
    private ToolResult executeInline(SkillDefinition skill, String resolvedPrompt) {
        StringBuilder result = new StringBuilder();
        result.append("<skill name=\"").append(skill.name()).append("\">\n");
        result.append(resolvedPrompt);
        result.append("\n</skill>");
        return new ToolResult(false, result.toString());
    }

    // ================================================================
    //  Fork Execution
    // ================================================================

    /**
     * Fork 模式：创建子 Agent 执行 skill prompt。
     */
    private ToolResult executeFork(SkillDefinition skill,
                                   String resolvedPrompt,
                                   ToolExecutionContext context) {
        if (subAgentRunner == null) {
            return new ToolResult(true,
                    "Fork execution unavailable: SubAgentRunner not configured. "
                            + "Skill '" + skill.name() + "' requires fork mode.");
        }

        // 构建 AgentDefinition for skill
        AgentDefinition agentDef = AgentDefinition.custom(
                "skill-" + skill.name(),
                "Execute skill: " + skill.name(),
                skill.description().isBlank()
                        ? "Execute the following skill task."
                        : skill.description(),
                skill.allowedTools(),   // null = all tools
                List.of("agent", "send_message"),  // 子 Agent 禁用递归
                false,
                12,
                skill.model()
        );

        java.util.function.Consumer<String> eventSink = event ->
                System.out.println("\u001B[90m    [skill:" + skill.name() + "] " + event + "\u001B[0m");

        AgentResult result = subAgentRunner.runSync(agentDef, resolvedPrompt, skill.name(), eventSink);

        if (result.status() == AgentResult.Status.FAILED) {
            return new ToolResult(true, "Skill fork execution failed: " + result.content());
        }

        return new ToolResult(false,
                "{\"status\":\"completed\","
                        + "\"skill\":\"" + escapeJson(skill.name()) + "\","
                        + "\"mode\":\"fork\","
                        + "\"content\":\"" + escapeJson(result.content()) + "\"}");
    }

    // ================================================================
    //  Helpers
    // ================================================================

    private String availableSkillNames() {
        List<SkillDefinition> skills = skillRegistry.allSkills();
        if (skills.isEmpty()) {
            return "(none)";
        }
        return skills.stream()
                .map(SkillDefinition::name)
                .collect(Collectors.joining(", "));
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
