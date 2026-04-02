package com.co.claudecode.demo.model.llm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 运行时配置收敛成一个对象。
 * <p>
 * 加载优先级（高 -> 低）：
 * <ol>
 *   <li>环境变量</li>
 *   <li>项目根目录 application.properties</li>
 *   <li>classpath 内置 application.properties（默认值）</li>
 * </ol>
 */
public record ModelRuntimeConfig(
        ModelProvider provider,
        String modelName,
        String apiKey,
        String baseUrl,
        int maxOutputTokens
) {

    /**
     * 从配置文件 + 环境变量加载运行时配置。
     */
    public static ModelRuntimeConfig load() {
        Properties props = loadProperties();

        ModelProvider provider = ModelProvider.fromEnv(
                resolve(props, "model.provider", "CLAUDE_CODE_DEMO_MODEL_PROVIDER")
        );

        return switch (provider) {
            case OPENAI -> new ModelRuntimeConfig(
                    provider,
                    resolve(props, "openai.model", "OPENAI_MODEL", "gpt-4.1-mini"),
                    resolveFirst(props,
                            new String[]{"openai.api-key"},
                            new String[]{"OPENAI_API_KEY"}
                    ),
                    resolve(props, "openai.base-url", "OPENAI_BASE_URL", "https://api.openai.com/v1"),
                    resolveInt(props, "max-output-tokens", "CLAUDE_CODE_MAX_OUTPUT_TOKENS", 4096)
            );
            case ANTHROPIC -> new ModelRuntimeConfig(
                    provider,
                    resolve(props, "anthropic.model", "ANTHROPIC_MODEL", "claude-3-5-sonnet-latest"),
                    resolveFirst(props,
                            new String[]{"anthropic.auth-token", "anthropic.api-key"},
                            new String[]{"ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY"}
                    ),
                    resolve(props, "anthropic.base-url", "ANTHROPIC_BASE_URL", "https://api.anthropic.com"),
                    resolveInt(props, "max-output-tokens", "CLAUDE_CODE_MAX_OUTPUT_TOKENS", 4096)
            );
            case RULES -> new ModelRuntimeConfig(provider, "rules", null, null, 4096);
        };
    }

    // ---- property loading ----

    private static Properties loadProperties() {
        Properties props = new Properties();

        // 1. classpath 内置默认值
        try (InputStream is = ModelRuntimeConfig.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException ignored) {
        }

        // 2. 项目根目录用户配置覆盖
        Path userConfig = Path.of("application.properties");
        if (Files.isRegularFile(userConfig)) {
            try (InputStream is = Files.newInputStream(userConfig)) {
                props.load(is);
            } catch (IOException ignored) {
            }
        }

        return props;
    }

    /**
     * 解析单个配置项：环境变量 > properties > fallback。
     */
    private static String resolve(Properties props, String propKey, String envKey, String fallback) {
        // 环境变量优先
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        // properties 文件
        String propValue = props.getProperty(propKey);
        if (propValue != null && !propValue.isBlank()) {
            return propValue;
        }
        return fallback;
    }

    /**
     * 解析配置项（无 fallback）。
     */
    private static String resolve(Properties props, String propKey, String envKey) {
        return resolve(props, propKey, envKey, null);
    }

    /**
     * 从多个候选 key 中取第一个非空值。
     * 用于同一语义有多个变量名的场景（如 auth-token vs api-key）。
     */
    private static String resolveFirst(Properties props, String[] propKeys, String[] envKeys) {
        // 先查环境变量
        for (String envKey : envKeys) {
            String envValue = System.getenv(envKey);
            if (envValue != null && !envValue.isBlank()) {
                return envValue;
            }
        }
        // 再查 properties
        for (String propKey : propKeys) {
            String propValue = props.getProperty(propKey);
            if (propValue != null && !propValue.isBlank()) {
                return propValue;
            }
        }
        return null;
    }

    /**
     * 解析整数配置项。
     */
    private static int resolveInt(Properties props, String propKey, String envKey, int fallback) {
        String value = resolve(props, propKey, envKey);
        if (value != null && !value.isBlank()) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }
}
