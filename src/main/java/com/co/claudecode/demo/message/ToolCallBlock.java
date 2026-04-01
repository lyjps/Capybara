package com.co.claudecode.demo.message;

import java.util.Map;

public record ToolCallBlock(String id, String toolName, Map<String, String> input) implements ContentBlock {

    public ToolCallBlock {
        id = id == null ? "" : id;
        toolName = toolName == null ? "" : toolName;
        input = input == null ? Map.of() : Map.copyOf(input);
    }

    @Override
    public String renderForModel() {
        return "[tool_call] " + toolName + " " + input;
    }
}
