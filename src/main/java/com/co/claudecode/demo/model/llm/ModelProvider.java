package com.co.claudecode.demo.model.llm;

public enum ModelProvider {
    RULES,
    OPENAI,
    ANTHROPIC;

    public static ModelProvider fromEnv(String value) {
        if (value == null || value.isBlank()) {
            return RULES;
        }
        return switch (value.trim().toLowerCase()) {
            case "openai" -> OPENAI;
            case "anthropic" -> ANTHROPIC;
            default -> RULES;
        };
    }
}
