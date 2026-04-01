package com.co.claudecode.demo.model.llm;

/**
 * 把运行时配置收敛成一个对象，是为了避免 provider 选择逻辑散落在入口、
 * provider 实现和 future feature 之间。这样后续接配置文件、CLI 参数、
 * 或 secrets manager 时，不需要重写核心装配逻辑。
 */
public record ModelRuntimeConfig(
        ModelProvider provider,
        String modelName,
        String apiKey,
        String baseUrl
) {

    public static ModelRuntimeConfig fromEnvironment() {
        ModelProvider provider = ModelProvider.fromEnv(System.getenv("CLAUDE_CODE_DEMO_MODEL_PROVIDER"));
        return switch (provider) {
            case OPENAI -> new ModelRuntimeConfig(
                    provider,
                    envOrDefault("OPENAI_MODEL", "gpt-4.1-mini"),
                    System.getenv("OPENAI_API_KEY"),
                    envOrDefault("OPENAI_BASE_URL", "https://api.openai.com/v1")
            );
            case ANTHROPIC -> new ModelRuntimeConfig(
                    provider,
                    envOrDefault("ANTHROPIC_MODEL", "claude-3-5-sonnet-latest"),
                    System.getenv("ANTHROPIC_API_KEY"),
                    envOrDefault("ANTHROPIC_BASE_URL", "https://api.anthropic.com")
            );
            case RULES -> new ModelRuntimeConfig(provider, "rules", null, null);
        };
    }

    private static String envOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
