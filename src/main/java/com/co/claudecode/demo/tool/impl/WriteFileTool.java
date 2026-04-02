package com.co.claudecode.demo.tool.impl;

import com.co.claudecode.demo.tool.Tool;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolMetadata;
import com.co.claudecode.demo.tool.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 让模型把结论写成产物，而不是只停留在对话里，
 * 是为了把“会推理”进一步推进到“会沉淀可交付结果”。
 */
public final class WriteFileTool implements Tool {

    private static final ToolMetadata METADATA = new ToolMetadata(
            "write_file",
            "将内容写入 artifact 输出目录中的文件。用于保存分析结论、摘要等产物。",
            false,
            false,
            true,
            ToolMetadata.PathDomain.ARTIFACT,
            "path",
            List.of(
                    new ToolMetadata.ParamInfo("path", "输出文件的相对路径，如 architecture-summary.md", true),
                    new ToolMetadata.ParamInfo("content", "要写入的文件内容", true)
            )
    );

    @Override
    public ToolMetadata metadata() {
        return METADATA;
    }

    @Override
    public void validate(Map<String, String> input) {
        String path = input.get("path");
        String content = input.get("content");
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("write_file 需要 path。");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("write_file 需要 content。");
        }
    }

    @Override
    public ToolResult execute(Map<String, String> input, ToolExecutionContext context) throws IOException {
        Path target = context.resolveArtifact(input.get("path"));
        Files.createDirectories(target.getParent());
        Files.writeString(target, input.get("content"), StandardCharsets.UTF_8);
        return new ToolResult(false, "已写入: " + target);
    }
}
