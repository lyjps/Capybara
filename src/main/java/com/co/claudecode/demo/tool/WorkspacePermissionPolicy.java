package com.co.claudecode.demo.tool;

import com.co.claudecode.demo.message.ToolCallBlock;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * 这个权限策略故意很保守。
 * demo 的重点是展示“能力”和“治理”分层，而不是做一个默认无边界的脚本。
 */
public final class WorkspacePermissionPolicy implements PermissionPolicy {

    @Override
    public PermissionDecision evaluate(Tool tool, ToolCallBlock call, ToolExecutionContext context) {
        ToolMetadata metadata = tool.metadata();
        if (metadata.pathDomain() == ToolMetadata.PathDomain.NONE) {
            return PermissionDecision.allow();
        }

        String pathKey = metadata.pathInputKey();
        String rawPath = call.input().getOrDefault(pathKey, ".");

        try {
            Path root = switch (metadata.pathDomain()) {
                case WORKSPACE -> context.workspaceRoot();
                case ARTIFACT -> context.artifactRoot();
                case NONE -> null;
            };
            Path resolved = switch (metadata.pathDomain()) {
                case WORKSPACE -> context.resolveWorkspace(rawPath);
                case ARTIFACT -> context.resolveArtifact(rawPath);
                case NONE -> null;
            };

            if (root != null && resolved != null && !resolved.startsWith(root)) {
                return PermissionDecision.deny("路径越界，已被权限层阻止。");
            }

            if (metadata.destructive() && metadata.pathDomain() != ToolMetadata.PathDomain.ARTIFACT) {
                return PermissionDecision.deny("写操作只允许落在 artifactRoot，避免污染被分析工作区。");
            }

            return PermissionDecision.allow();
        } catch (InvalidPathException error) {
            return PermissionDecision.deny("非法路径: " + error.getInput());
        }
    }
}
