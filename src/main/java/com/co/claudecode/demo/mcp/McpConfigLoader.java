package com.co.claudecode.demo.mcp;

import com.co.claudecode.demo.mcp.protocol.SimpleJsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP 服务器配置加载器。
 * <p>
 * 对应 TS 版 config.ts 中的 getClaudeCodeMcpConfigs。
 * 从多个来源加载 MCP 服务器配置，按优先级合并。
 * <p>
 * 配置源优先级（高 → 低）：
 * <ol>
 *   <li>项目级：{@code <workspaceRoot>/.mcp.json}</li>
 *   <li>用户级：{@code ~/.claude/settings.json} → mcpServers</li>
 * </ol>
 * <p>
 * 支持环境变量展开：{@code ${VAR}} 和 {@code ${VAR:-default}}。
 */
public final class McpConfigLoader {

    /** 项目级配置文件名。 */
    public static final String PROJECT_CONFIG_FILE = ".mcp.json";

    /** 用户级配置目录。 */
    public static final String USER_CONFIG_DIR = ".claude";

    /** 用户级配置文件名。 */
    public static final String USER_CONFIG_FILE = "settings.json";

    /** 环境变量匹配正则。 */
    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private McpConfigLoader() {
    }

    // ================================================================
    //  公开方法
    // ================================================================

    /**
     * 从所有来源加载 MCP 配置。
     *
     * @param workspaceRoot 工作区根目录
     * @return 合并后的服务器配置列表
     */
    public static List<McpServerConfig> loadConfigs(Path workspaceRoot) {
        Map<String, McpServerConfig> merged = new LinkedHashMap<>();

        // 1. 加载用户级配置（低优先级）
        Path userConfig = Path.of(System.getProperty("user.home"))
                .resolve(USER_CONFIG_DIR)
                .resolve(USER_CONFIG_FILE);
        if (Files.exists(userConfig)) {
            try {
                List<McpServerConfig> userConfigs = parseSettingsFile(userConfig);
                for (McpServerConfig config : userConfigs) {
                    merged.put(config.name(), config);
                }
            } catch (IOException e) {
                System.err.println("[MCP] Failed to load user config: " + e.getMessage());
            }
        }

        // 2. 加载项目级配置（高优先级，覆盖同名）
        if (workspaceRoot != null) {
            Path projectConfig = workspaceRoot.resolve(PROJECT_CONFIG_FILE);
            if (Files.exists(projectConfig)) {
                try {
                    List<McpServerConfig> projectConfigs = parseMcpJsonFile(projectConfig);
                    for (McpServerConfig config : projectConfigs) {
                        merged.put(config.name(), config);
                    }
                } catch (IOException e) {
                    System.err.println("[MCP] Failed to load project config: " + e.getMessage());
                }
            }
        }

        // 3. 对所有配置执行环境变量展开
        List<McpServerConfig> result = new ArrayList<>();
        for (McpServerConfig config : merged.values()) {
            result.add(expandConfig(config));
        }

        return result;
    }

    // ================================================================
    //  配置文件解析
    // ================================================================

    /**
     * 解析 .mcp.json 文件。
     * 格式：{@code { "mcpServers": { "name": { ... } } }}
     */
    public static List<McpServerConfig> parseMcpJsonFile(Path configFile) throws IOException {
        String content = Files.readString(configFile);
        return parseMcpServersJson(content);
    }

    /**
     * 解析 settings.json 文件中的 mcpServers 部分。
     */
    public static List<McpServerConfig> parseSettingsFile(Path settingsFile) throws IOException {
        String content = Files.readString(settingsFile);
        return parseMcpServersJson(content);
    }

    /**
     * 从 JSON 字符串中解析 mcpServers 配置。
     */
    public static List<McpServerConfig> parseMcpServersJson(String json) {
        if (json == null || json.isBlank()) return List.of();

        String mcpServers = SimpleJsonParser.extractField(json, "mcpServers");
        if (mcpServers == null || !mcpServers.trim().startsWith("{")) {
            return List.of();
        }

        Map<String, String> servers = SimpleJsonParser.parseFlat(mcpServers);
        List<McpServerConfig> configs = new ArrayList<>();

        for (var entry : servers.entrySet()) {
            String name = entry.getKey();
            String serverJson = entry.getValue();

            if (serverJson == null || !serverJson.trim().startsWith("{")) continue;

            McpServerConfig config = parseServerConfig(name, serverJson);
            if (config != null) {
                configs.add(config);
            }
        }

        return configs;
    }

    // ================================================================
    //  环境变量展开
    // ================================================================

    /**
     * 展开字符串中的环境变量。
     * <p>
     * 支持：
     * <ul>
     *   <li>{@code ${VAR}} — 替换为环境变量值，未找到保留原始</li>
     *   <li>{@code ${VAR:-default}} — 替换为环境变量值，未找到使用默认值</li>
     * </ul>
     */
    public static String expandEnvVars(String value) {
        if (value == null || !value.contains("${")) return value;

        Matcher matcher = ENV_VAR_PATTERN.matcher(value);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String varExpr = matcher.group(1);
            String replacement;

            // 检查是否有默认值: VAR:-default
            int defaultSep = varExpr.indexOf(":-");
            if (defaultSep >= 0) {
                String varName = varExpr.substring(0, defaultSep);
                String defaultValue = varExpr.substring(defaultSep + 2);
                String envValue = System.getenv(varName);
                replacement = (envValue != null && !envValue.isEmpty()) ? envValue : defaultValue;
            } else {
                String envValue = System.getenv(varExpr);
                replacement = envValue != null ? envValue : matcher.group(0); // 保留原始
            }

            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    /**
     * 对配置的所有字段执行环境变量展开。
     */
    public static McpServerConfig expandConfig(McpServerConfig config) {
        if (config.transportType() == McpTransportType.STDIO) {
            // 展开 command, args, env
            String expandedCommand = expandEnvVars(config.command());
            List<String> expandedArgs = config.args().stream()
                    .map(McpConfigLoader::expandEnvVars)
                    .toList();
            Map<String, String> expandedEnv = new LinkedHashMap<>();
            config.env().forEach((k, v) -> expandedEnv.put(k, expandEnvVars(v)));

            return new McpServerConfig(config.name(), config.transportType(),
                    expandedCommand, expandedArgs, expandedEnv,
                    config.url(), config.headers(), config.auth(), config.disabled());
        } else {
            // 展开 url, headers
            String expandedUrl = expandEnvVars(config.url());
            Map<String, String> expandedHeaders = new LinkedHashMap<>();
            config.headers().forEach((k, v) -> expandedHeaders.put(k, expandEnvVars(v)));

            // 展开 auth 字段中的环境变量
            com.co.claudecode.demo.mcp.auth.McpAuthConfig expandedAuth = null;
            if (config.auth() != null) {
                expandedAuth = new com.co.claudecode.demo.mcp.auth.McpAuthConfig(
                        expandEnvVars(config.auth().tokenEndpoint()),
                        expandEnvVars(config.auth().clientId()),
                        expandEnvVars(config.auth().clientSecret()),
                        expandEnvVars(config.auth().audience())
                );
            }

            return new McpServerConfig(config.name(), config.transportType(),
                    config.command(), config.args(), config.env(),
                    expandedUrl, expandedHeaders, expandedAuth, config.disabled());
        }
    }

    // ================================================================
    //  内部方法
    // ================================================================

    private static McpServerConfig parseServerConfig(String name, String serverJson) {
        String type = SimpleJsonParser.extractField(serverJson, "type");
        McpTransportType transportType = parseTransportType(type);

        String disabledStr = SimpleJsonParser.extractField(serverJson, "disabled");
        boolean disabled = "true".equals(disabledStr);

        if (transportType == McpTransportType.STDIO) {
            String command = SimpleJsonParser.extractField(serverJson, "command");
            if (command == null || command.isBlank()) return null;

            List<String> args = parseStringArray(
                    SimpleJsonParser.extractField(serverJson, "args"));
            Map<String, String> env = parseStringMap(
                    SimpleJsonParser.extractField(serverJson, "env"));

            return new McpServerConfig(name, transportType, command, args, env,
                    null, Map.of(), null, disabled);
        } else {
            String url = SimpleJsonParser.extractField(serverJson, "url");
            if (url == null || url.isBlank()) return null;

            Map<String, String> headers = parseStringMap(
                    SimpleJsonParser.extractField(serverJson, "headers"));

            // 解析 auth 块（可选）
            com.co.claudecode.demo.mcp.auth.McpAuthConfig auth = parseAuthConfig(serverJson);

            return new McpServerConfig(name, transportType, null, List.of(), Map.of(),
                    url, headers, auth, disabled);
        }
    }

    /**
     * 解析 auth 配置块。
     */
    private static com.co.claudecode.demo.mcp.auth.McpAuthConfig parseAuthConfig(String serverJson) {
        String authJson = SimpleJsonParser.extractField(serverJson, "auth");
        if (authJson == null || !authJson.trim().startsWith("{")) {
            return null;
        }

        String tokenEndpoint = SimpleJsonParser.extractField(authJson, "tokenEndpoint");
        String clientId = SimpleJsonParser.extractField(authJson, "clientId");
        String clientSecret = SimpleJsonParser.extractField(authJson, "clientSecret");
        String audience = SimpleJsonParser.extractField(authJson, "audience");

        if (tokenEndpoint == null || clientId == null || clientSecret == null) {
            return null;
        }

        return new com.co.claudecode.demo.mcp.auth.McpAuthConfig(
                tokenEndpoint, clientId, clientSecret, audience);
    }

    private static McpTransportType parseTransportType(String type) {
        if (type == null || type.isBlank() || "stdio".equalsIgnoreCase(type)) {
            return McpTransportType.STDIO;
        }
        if ("sse".equalsIgnoreCase(type)) {
            return McpTransportType.SSE;
        }
        if ("http".equalsIgnoreCase(type)) {
            return McpTransportType.HTTP;
        }
        return McpTransportType.STDIO; // 默认
    }

    private static List<String> parseStringArray(String json) {
        if (json == null || !json.trim().startsWith("[")) return List.of();
        List<String> elements = SimpleJsonParser.parseArrayRaw(json);
        List<String> result = new ArrayList<>();
        for (String elem : elements) {
            String cleaned = elem.trim();
            if (cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
                cleaned = cleaned.substring(1, cleaned.length() - 1);
                cleaned = SimpleJsonParser.unescapeJson(cleaned);
            }
            result.add(cleaned);
        }
        return result;
    }

    private static Map<String, String> parseStringMap(String json) {
        if (json == null || !json.trim().startsWith("{")) return Map.of();
        return SimpleJsonParser.parseFlat(json);
    }
}
