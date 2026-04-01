package com.co.claudecode.demo.message;

public record ToolResultBlock(String toolUseId, String toolName, boolean error, String content) implements ContentBlock {

    public ToolResultBlock {
        toolUseId = toolUseId == null ? "" : toolUseId;
        toolName = toolName == null ? "" : toolName;
        content = content == null ? "" : content;
    }

    @Override
    public String renderForModel() {
        String tag = error ? "[tool_error]" : "[tool_result]";
        return tag + " " + toolName + "\n" + content;
    }
}
