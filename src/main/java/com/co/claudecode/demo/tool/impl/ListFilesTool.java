package com.co.claudecode.demo.tool.impl;

import com.co.claudecode.demo.tool.Tool;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolMetadata;
import com.co.claudecode.demo.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 第一轮先建立工作区轮廓，比直接读大文件更划算。
 * agent 最怕的是一开始就抓住错误的局部细节，所以先看目录，再决定读什么。
 */
public final class ListFilesTool implements Tool {

    private static final ToolMetadata METADATA = new ToolMetadata(
            "list_files",
            "列出工作区中的文件和目录结构。返回格式：每行以 FILE 或 DIR 开头，后跟相对路径。",
            true,
            true,
            false,
            ToolMetadata.PathDomain.WORKSPACE,
            "path",
            List.of(
                    new ToolMetadata.ParamInfo("path", "要列出的目录相对路径，默认 \".\"", false),
                    new ToolMetadata.ParamInfo("depth", "递归深度，1-6 之间，默认 3", false)
            )
    );

    @Override
    public ToolMetadata metadata() {
        return METADATA;
    }

    @Override
    public void validate(Map<String, String> input) {
        int depth = Integer.parseInt(input.getOrDefault("depth", "3"));
        if (depth < 1 || depth > 6) {
            throw new IllegalArgumentException("depth 需要落在 1 到 6 之间。");
        }
    }

    @Override
    public ToolResult execute(Map<String, String> input, ToolExecutionContext context) throws IOException {
        Path start = context.resolveWorkspace(input.getOrDefault("path", "."));
        int depth = Integer.parseInt(input.getOrDefault("depth", "3"));

        if (!Files.exists(start)) {
            return new ToolResult(true, "路径不存在: " + start);
        }

        try (Stream<Path> stream = Files.walk(start, depth)) {
            String content = stream
                    .filter(path -> !path.equals(start))
                    .filter(this::isUsefulPath)
                    .sorted(Comparator.naturalOrder())
                    .limit(200)
                    .map(path -> (Files.isDirectory(path) ? "DIR  " : "FILE ")
                            + context.workspaceRoot().relativize(path))
                    .collect(Collectors.joining("\n"));
            return new ToolResult(false, content);
        }
    }

    private boolean isUsefulPath(Path path) {
        for (Path segment : path) {
            String name = segment.toString();
            if (name.startsWith(".")) {
                return false;
            }
            if (name.equals("node_modules")
                    || name.equals("target")
                    || name.equals("dist")
                    || name.equals("build")
                    || name.equals("out")) {
                return false;
            }
        }
        return true;
    }
}
