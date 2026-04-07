package com.co.claudecode.demo.skill;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Skill 文件加载器 -- 从磁盘扫描并解析 skill 文件。
 * <p>
 * 支持两种 skill 文件布局：
 * <ul>
 *   <li><b>扁平模式</b>：{@code skills/my-skill.md} -- 直接放在 skills 目录下的 .md 文件</li>
 *   <li><b>目录模式</b>：{@code skills/my-skill/SKILL.md} -- 子目录（或符号链接）中的 SKILL.md 文件</li>
 * </ul>
 * <p>
 * 扫描目录（按优先级，项目级覆盖用户级同名 skill）：
 * <ol>
 *   <li>{@code ~/.claude/skills/} -- 用户级</li>
 *   <li>{@code <workspaceRoot>/.claude/skills/} -- 项目级</li>
 * </ol>
 * <p>
 * 每个 .md 文件包含 YAML frontmatter（--- 分隔）和 Markdown body。
 * YAML 解析为零外部依赖的手工实现，支持常见简单格式。
 */
public final class SkillLoader {

    private static final Logger LOG = Logger.getLogger(SkillLoader.class.getName());
    private static final String FRONTMATTER_DELIMITER = "---";
    private static final String SKILL_FILE_NAME = "SKILL.md";

    private SkillLoader() {
    }

    // ================================================================
    //  Public API
    // ================================================================

    /**
     * 加载所有 skill。先扫描用户级，再扫描项目级，项目级覆盖用户级同名 skill。
     *
     * @param workspaceRoot 项目根目录（nullable）
     * @return 合并后的 skill 列表（按名称去重，项目级优先）
     */
    public static List<SkillDefinition> loadAll(Path workspaceRoot) {
        Map<String, SkillDefinition> merged = new LinkedHashMap<>();

        // 1. 用户级 ~/.claude/skills/
        Path userSkillDir = getUserSkillDir();
        if (userSkillDir != null) {
            for (SkillDefinition skill : loadFromDirectory(userSkillDir, SkillDefinition.Source.USER)) {
                merged.put(skill.name(), skill);
            }
        }

        // 2. 项目级 <workspaceRoot>/.claude/skills/
        if (workspaceRoot != null) {
            Path projectSkillDir = workspaceRoot.resolve(".claude").resolve("skills");
            for (SkillDefinition skill : loadFromDirectory(projectSkillDir, SkillDefinition.Source.PROJECT)) {
                merged.put(skill.name(), skill); // 项目级覆盖用户级
            }
        }

        return List.copyOf(merged.values());
    }

    /**
     * 从指定目录加载所有 skill 文件。
     * <p>
     * 支持两种布局：
     * <ul>
     *   <li>扁平模式：直接扫描 {@code directory/*.md}</li>
     *   <li>目录模式：扫描 {@code directory/<name>/SKILL.md}（子目录或符号链接）</li>
     * </ul>
     *
     * @param directory 目录路径
     * @param source    来源级别
     * @return 解析成功的 skill 列表
     */
    public static List<SkillDefinition> loadFromDirectory(Path directory, SkillDefinition.Source source) {
        if (directory == null || !Files.isDirectory(directory)) {
            return List.of();
        }

        List<SkillDefinition> skills = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                try {
                    if (Files.isRegularFile(entry) && entry.getFileName().toString().endsWith(".md")) {
                        // 扁平模式：skills/my-skill.md
                        SkillDefinition skill = parseSkillFile(entry, source);
                        if (skill != null) {
                            skills.add(skill);
                        }
                    } else if (Files.isDirectory(entry)) {
                        // 目录模式：skills/my-skill/SKILL.md
                        Path skillMd = entry.resolve(SKILL_FILE_NAME);
                        if (Files.isRegularFile(skillMd)) {
                            SkillDefinition skill = parseSkillFile(skillMd, source);
                            if (skill != null) {
                                skills.add(skill);
                            }
                        }
                    }
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to parse skill entry: " + entry, e);
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to read skill directory: " + directory, e);
        }
        return skills;
    }

    // ================================================================
    //  File Parsing
    // ================================================================

    /**
     * 从单个 .md 文件解析 SkillDefinition。
     *
     * @param file   .md 文件路径
     * @param source 来源级别
     * @return 解析成功的 SkillDefinition，或 null
     */
    static SkillDefinition parseSkillFile(Path file, SkillDefinition.Source source) throws IOException {
        String content = Files.readString(file);
        if (content.isBlank()) {
            return null;
        }

        // 分离 frontmatter 和 body
        String frontmatterStr = null;
        String body;

        String trimmed = content.strip();
        if (trimmed.startsWith(FRONTMATTER_DELIMITER)) {
            int secondDelim = trimmed.indexOf(FRONTMATTER_DELIMITER, FRONTMATTER_DELIMITER.length());
            if (secondDelim > 0) {
                frontmatterStr = trimmed.substring(FRONTMATTER_DELIMITER.length(), secondDelim).strip();
                body = trimmed.substring(secondDelim + FRONTMATTER_DELIMITER.length()).strip();
            } else {
                // 有开头 --- 但无结尾 ---，全部视为 body
                body = content.strip();
            }
        } else {
            body = content.strip();
        }

        // 解析 frontmatter
        Map<String, Object> meta = frontmatterStr != null
                ? parseFrontmatter(frontmatterStr) : Map.of();

        // 推导默认名称：
        //   SKILL.md → 使用父目录名
        //   其他 .md  → 去掉 .md 后缀
        String fileName = file.getFileName().toString();
        String defaultName;
        if (SKILL_FILE_NAME.equals(fileName) && file.getParent() != null) {
            defaultName = file.getParent().getFileName().toString();
        } else {
            defaultName = fileName.endsWith(".md")
                    ? fileName.substring(0, fileName.length() - 3)
                    : fileName;
        }

        // 提取字段
        String name = getStringValue(meta, "name", defaultName);
        String description = getStringValue(meta, "description", "");
        String whenToUse = getStringValue(meta, "when_to_use",
                getStringValue(meta, "when-to-use", ""));
        List<String> allowedTools = getStringList(meta, "allowed-tools",
                getStringList(meta, "allowed_tools", null));
        String model = getStringValue(meta, "model", null);

        String contextStr = getStringValue(meta, "context", "inline");
        SkillDefinition.ExecutionMode executionMode =
                "fork".equalsIgnoreCase(contextStr)
                        ? SkillDefinition.ExecutionMode.FORK
                        : SkillDefinition.ExecutionMode.INLINE;

        List<SkillDefinition.SkillArgument> arguments = parseArguments(meta);

        return new SkillDefinition(
                name, description, whenToUse, allowedTools,
                model, executionMode, arguments, body, file, source);
    }

    // ================================================================
    //  YAML Frontmatter Parser (zero-dep)
    // ================================================================

    /**
     * 解析简单的 YAML frontmatter。
     * <p>
     * 支持：
     * - 简单键值对：{@code key: value}
     * - 引号字符串：{@code key: "value"} 或 {@code key: 'value'}
     * - 内联列表：{@code key: [a, b, c]}
     * - 缩进列表：{@code key:} 后跟 {@code - item} 行
     * - 嵌套对象列表（arguments）：{@code - name: xxx} 格式
     * - 布尔值：{@code true/false/yes/no}
     *
     * @param yaml YAML 字符串（不包含 --- 分隔符）
     * @return 解析后的键值映射
     */
    static Map<String, Object> parseFrontmatter(String yaml) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (yaml == null || yaml.isBlank()) {
            return result;
        }

        String[] lines = yaml.split("\n");
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmedLine = line.strip();

            // 跳过空行和注释
            if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) {
                i++;
                continue;
            }

            int colonIdx = trimmedLine.indexOf(':');
            if (colonIdx <= 0) {
                i++;
                continue;
            }

            String key = trimmedLine.substring(0, colonIdx).strip();
            String valueStr = trimmedLine.substring(colonIdx + 1).strip();

            if (valueStr.isEmpty()) {
                // 可能是缩进列表或嵌套对象列表
                List<Object> listItems = new ArrayList<>();
                i++;
                while (i < lines.length) {
                    String nextLine = lines[i];
                    String nextTrimmed = nextLine.strip();
                    if (nextTrimmed.isEmpty()) {
                        i++;
                        continue;
                    }
                    // 检查是否是列表项
                    if (nextTrimmed.startsWith("- ")) {
                        String itemContent = nextTrimmed.substring(2).strip();
                        // 检查是否是嵌套对象（如 - name: xxx）
                        if (itemContent.contains(":")) {
                            Map<String, String> nestedObj = new LinkedHashMap<>();
                            nestedObj.put(
                                    itemContent.substring(0, itemContent.indexOf(':')).strip(),
                                    unquote(itemContent.substring(itemContent.indexOf(':') + 1).strip()));
                            i++;
                            // 继续读取同一对象的后续属性（缩进更深的行）
                            while (i < lines.length) {
                                String subLine = lines[i];
                                String subTrimmed = subLine.strip();
                                if (subTrimmed.isEmpty()) {
                                    i++;
                                    continue;
                                }
                                // 如果不是以 - 开头且包含 :，是同一对象的属性
                                if (!subTrimmed.startsWith("- ") && subTrimmed.contains(":")
                                        && (subLine.startsWith("  ") || subLine.startsWith("\t"))) {
                                    int subColon = subTrimmed.indexOf(':');
                                    nestedObj.put(
                                            subTrimmed.substring(0, subColon).strip(),
                                            unquote(subTrimmed.substring(subColon + 1).strip()));
                                    i++;
                                } else {
                                    break;
                                }
                            }
                            listItems.add(nestedObj);
                        } else {
                            listItems.add(unquote(itemContent));
                            i++;
                        }
                    } else if (nextLine.startsWith("  ") || nextLine.startsWith("\t")) {
                        // 缩进的非列表项 — 可能是继续行
                        i++;
                    } else {
                        break; // 回到顶级
                    }
                }
                result.put(key, listItems);
            } else if (valueStr.startsWith("[") && valueStr.endsWith("]")) {
                // 内联列表 [a, b, c]
                String inner = valueStr.substring(1, valueStr.length() - 1).strip();
                if (inner.isEmpty()) {
                    result.put(key, List.of());
                } else {
                    List<String> items = new ArrayList<>();
                    for (String item : inner.split(",")) {
                        items.add(unquote(item.strip()));
                    }
                    result.put(key, items);
                }
                i++;
            } else {
                // 简单值
                result.put(key, unquote(valueStr));
                i++;
            }
        }

        return result;
    }

    // ================================================================
    //  Helpers
    // ================================================================

    /**
     * 获取用户级 skill 目录路径。
     */
    static Path getUserSkillDir() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return null;
        }
        return Path.of(home, ".claude", "skills");
    }

    /**
     * 从 meta map 中解析 arguments 列表。
     */
    @SuppressWarnings("unchecked")
    private static List<SkillDefinition.SkillArgument> parseArguments(Map<String, Object> meta) {
        Object argsObj = meta.get("arguments");
        if (!(argsObj instanceof List<?> argsList)) {
            return List.of();
        }

        List<SkillDefinition.SkillArgument> result = new ArrayList<>();
        for (Object item : argsList) {
            if (item instanceof Map<?, ?> map) {
                Map<String, String> argMap = (Map<String, String>) map;
                String argName = argMap.getOrDefault("name", "");
                String argDesc = argMap.getOrDefault("description", "");
                boolean argRequired = parseBooleanValue(argMap.getOrDefault("required", "false"));
                if (!argName.isBlank()) {
                    result.add(new SkillDefinition.SkillArgument(argName, argDesc, argRequired));
                }
            }
        }
        return result;
    }

    /**
     * 解析布尔值（支持 true/false/yes/no）。
     */
    private static boolean parseBooleanValue(String value) {
        if (value == null) return false;
        String v = value.strip().toLowerCase();
        return "true".equals(v) || "yes".equals(v) || "1".equals(v);
    }

    /**
     * 从 meta map 获取字符串值。
     */
    private static String getStringValue(Map<String, Object> meta, String key, String defaultValue) {
        Object v = meta.get(key);
        if (v instanceof String s && !s.isBlank()) {
            return s;
        }
        return defaultValue;
    }

    /**
     * 从 meta map 获取字符串列表。
     */
    @SuppressWarnings("unchecked")
    private static List<String> getStringList(Map<String, Object> meta, String key, List<String> defaultValue) {
        Object v = meta.get(key);
        if (v instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String s) {
                    result.add(s);
                }
            }
            return result.isEmpty() ? defaultValue : result;
        }
        return defaultValue;
    }

    /**
     * 去除引号包裹。
     */
    private static String unquote(String s) {
        if (s == null) return "";
        if ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
