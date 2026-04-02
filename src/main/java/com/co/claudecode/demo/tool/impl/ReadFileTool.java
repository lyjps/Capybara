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
 * 把输出上限放在 tool 层，而不是放在模型层之后再截断，
 * 是因为最便宜的上下文治理永远发生在信息生产端。
 */
public final class ReadFileTool implements Tool {

    private static final int MAX_CHARS = 6000;

    private static final ToolMetadata METADATA = new ToolMetadata(
            "read_file",
            "读取工作区中的文件内容。返回格式：第一行是 PATH: 相对路径，后续是文件内容（超过 6000 字符会截断）。",
            true,
            true,
            false,
            ToolMetadata.PathDomain.WORKSPACE,
            "path",
            List.of(
                    new ToolMetadata.ParamInfo("path", "要读取的文件相对路径", true)
            )
    );

    @Override
    public ToolMetadata metadata() {
        return METADATA;
    }

    @Override
    public void validate(Map<String, String> input) {
        String path = input.get("path");
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("read_file 需要 path。");
        }
    }

    @Override
    public ToolResult execute(Map<String, String> input, ToolExecutionContext context) throws IOException {
        Path path = context.resolveWorkspace(input.get("path"));
        if (!Files.exists(path) || Files.isDirectory(path)) {
            return new ToolResult(true, "不是可读取文件: " + path);
        }

        String content = Files.readString(path, StandardCharsets.UTF_8);
        boolean truncated = content.length() > MAX_CHARS;
        String output = truncated ? content.substring(0, MAX_CHARS) + "\n...[truncated]" : content;
        String relative = context.workspaceRoot().relativize(path).toString();
        return new ToolResult(false, "PATH: " + relative + "\n" + output);
    }
}
