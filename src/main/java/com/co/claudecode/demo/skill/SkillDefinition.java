package com.co.claudecode.demo.skill;

import java.nio.file.Path;
import java.util.List;

/**
 * Skill 定义 -- 从 .md 文件解析出的完整 skill 描述。
 * <p>
 * 对应 TS 原版 {@code SkillConfig} + {@code SkillMetadata} 的合并。
 * 每个 skill 由 YAML frontmatter（元数据）+ Markdown body（prompt 模板）组成。
 *
 * @param name           skill 名称（frontmatter {@code name} 字段，或从文件名推导）
 * @param description    描述（供模型和用户参考）
 * @param whenToUse      使用场景描述
 * @param allowedTools   允许使用的工具列表（null 表示允许全部）
 * @param model          模型偏好（nullable，如 "sonnet"、"opus"）
 * @param context        执行模式（inline / fork）
 * @param arguments      参数定义列表
 * @param promptTemplate Markdown body，即 skill 的 prompt 模板
 * @param sourceFile     来源文件路径
 * @param source         来源级别（USER / PROJECT）
 */
public record SkillDefinition(
        String name,
        String description,
        String whenToUse,
        List<String> allowedTools,
        String model,
        ExecutionMode context,
        List<SkillArgument> arguments,
        String promptTemplate,
        Path sourceFile,
        Source source
) {

    /**
     * Skill 执行模式。
     */
    public enum ExecutionMode {
        /** 将 prompt 注入当前对话，模型在当前上下文中执行。 */
        INLINE,
        /** 创建子 Agent，在独立上下文中执行。 */
        FORK
    }

    /**
     * Skill 来源级别。
     */
    public enum Source {
        /** 用户级 (~/.claude/skills/) */
        USER,
        /** 项目级 (<project>/.claude/skills/) */
        PROJECT
    }

    /**
     * Skill 参数定义。
     */
    public record SkillArgument(String name, String description, boolean required) {
        public SkillArgument {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Skill argument name must not be blank");
            }
            description = description == null ? "" : description;
        }
    }

    public SkillDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Skill name must not be blank");
        }
        description = description == null ? "" : description;
        whenToUse = whenToUse == null ? "" : whenToUse;
        allowedTools = allowedTools == null ? null : List.copyOf(allowedTools);
        context = context == null ? ExecutionMode.INLINE : context;
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        promptTemplate = promptTemplate == null ? "" : promptTemplate;
    }

    /**
     * 将 prompt 模板中的 $ARGUMENTS 替换为实际参数。
     *
     * @param args 用户传入的参数字符串
     * @return 替换后的 prompt
     */
    public String resolvePrompt(String args) {
        String resolved = promptTemplate;
        if (args != null && !args.isBlank()) {
            resolved = resolved.replace("$ARGUMENTS", args);
        } else {
            resolved = resolved.replace("$ARGUMENTS", "");
        }
        return resolved.strip();
    }
}
