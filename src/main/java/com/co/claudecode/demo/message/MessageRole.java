package com.co.claudecode.demo.message;

public enum MessageRole {
    SYSTEM("SYSTEM"),
    USER("USER"),
    ASSISTANT("ASSISTANT");

    private final String label;

    MessageRole(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
