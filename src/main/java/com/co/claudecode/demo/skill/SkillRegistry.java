package com.co.claudecode.demo.skill;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill 注册表 -- 管理所有已加载的 skill。
 * <p>
 * 提供按名称查找、全部列出、生成 skill 列表描述（注入 system prompt）。
 */
public final class SkillRegistry {

    private final Map<String, SkillDefinition> skillsByName = new LinkedHashMap<>();

    public SkillRegistry(List<SkillDefinition> skills) {
        for (SkillDefinition skill : skills) {
            skillsByName.put(skill.name(), skill);
        }
    }

    /**
     * 按名称查找 skill。
     *
     * @param name skill 名称
     * @return 匹配的 SkillDefinition，未找到返回 null
     */
    public SkillDefinition findByName(String name) {
        if (name == null) return null;
        // 精确匹配
        SkillDefinition found = skillsByName.get(name);
        if (found != null) return found;

        // 尝试去掉文件扩展名匹配
        if (name.endsWith(".md")) {
            return skillsByName.get(name.substring(0, name.length() - 3));
        }
        return null;
    }

    /**
     * 返回所有已注册的 skill。
     */
    public List<SkillDefinition> allSkills() {
        return List.copyOf(skillsByName.values());
    }

    /**
     * 是否有 skill 可用。
     */
    public boolean hasSkills() {
        return !skillsByName.isEmpty();
    }

    /**
     * skill 总数。
     */
    public int size() {
        return skillsByName.size();
    }

    /**
     * 生成 skill 列表描述，用于注入到 system prompt。
     * <p>
     * 格式示例：
     * <pre>
     * 以下 skills 可通过 Skill 工具调用，或用户通过 /skill_name 斜杠命令触发：
     *
     * - **my-skill**: 描述... (使用场景: ...)
     *   参数: arg1 (required) - 参数描述
     * </pre>
     *
     * @return 格式化的 skill 列表
     */
    public String buildSkillListing() {
        if (skillsByName.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("以下 skills 可通过 Skill 工具调用，或用户通过 /skill_name 斜杠命令触发：\n\n");

        for (SkillDefinition skill : skillsByName.values()) {
            sb.append("- **").append(skill.name()).append("**");

            if (!skill.description().isBlank()) {
                sb.append(": ").append(skill.description());
            }

            if (!skill.whenToUse().isBlank()) {
                sb.append(" (使用场景: ").append(skill.whenToUse()).append(")");
            }

            sb.append('\n');

            // 参数描述
            if (!skill.arguments().isEmpty()) {
                sb.append("  参数: ");
                List<SkillDefinition.SkillArgument> args = skill.arguments();
                for (int i = 0; i < args.size(); i++) {
                    SkillDefinition.SkillArgument arg = args.get(i);
                    if (i > 0) sb.append(", ");
                    sb.append(arg.name());
                    if (arg.required()) {
                        sb.append(" (required)");
                    }
                    if (!arg.description().isBlank()) {
                        sb.append(" - ").append(arg.description());
                    }
                }
                sb.append('\n');
            }
        }

        return sb.toString().stripTrailing();
    }
}
