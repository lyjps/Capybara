package com.co.claudecode.demo.tool;

import java.nio.file.Path;

/**
 * 把“读哪里”和“写哪里”显式拆开，是为了让分析型 agent 默认更安全。
 * 真实系统里读范围往往比写范围大，如果共用一个 root，最容易把 demo
 * 做成无意中污染工作区的脚本。
 */
public record ToolExecutionContext(Path workspaceRoot, Path artifactRoot) {

    public ToolExecutionContext {
        workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        artifactRoot = artifactRoot.toAbsolutePath().normalize();
    }

    public Path resolveWorkspace(String relativePath) {
        return workspaceRoot.resolve(relativePath).normalize();
    }

    public Path resolveArtifact(String relativePath) {
        return artifactRoot.resolve(relativePath).normalize();
    }
}
