package com.co.claudecode.demo.tool.impl;

import com.co.claudecode.demo.task.Task;
import com.co.claudecode.demo.task.TaskStatus;
import com.co.claudecode.demo.task.TaskStore;
import com.co.claudecode.demo.tool.Tool;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolMetadata;
import com.co.claudecode.demo.tool.ToolResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 更新任务工具。
 * <p>
 * 对应 TS 原版 {@code TaskUpdateTool}。
 * 支持更新 status、subject、description、owner、addBlocks、addBlockedBy。
 * 特殊 status "deleted" 会删除任务。
 */
public final class TaskUpdateTool implements Tool {

    private final TaskStore taskStore;

    public TaskUpdateTool(TaskStore taskStore) {
        this.taskStore = taskStore;
    }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                "task_update", "Update a task in the task list",
                false, true, false,
                ToolMetadata.PathDomain.NONE, null,
                List.of(
                        new ToolMetadata.ParamInfo("taskId", "The ID of the task to update", true),
                        new ToolMetadata.ParamInfo("status", "New status: pending, in_progress, completed, or deleted", false),
                        new ToolMetadata.ParamInfo("subject", "New subject for the task", false),
                        new ToolMetadata.ParamInfo("description", "New description for the task", false),
                        new ToolMetadata.ParamInfo("owner", "New owner for the task", false),
                        new ToolMetadata.ParamInfo("addBlocks", "Comma-separated task IDs that this task blocks", false),
                        new ToolMetadata.ParamInfo("addBlockedBy", "Comma-separated task IDs that block this task", false)
                ));
    }

    @Override
    public ToolResult execute(Map<String, String> input, ToolExecutionContext context) {
        String taskId = input.get("taskId");
        if (taskId == null || taskId.isBlank()) {
            return new ToolResult(true, "Error: taskId is required");
        }

        String statusStr = input.get("status");

        // 特殊处理 "deleted" — 删除任务
        if ("deleted".equalsIgnoreCase(statusStr)) {
            boolean removed = taskStore.deleteTask(taskId);
            if (!removed) {
                return new ToolResult(true, "Error: Task #" + taskId + " not found");
            }
            return new ToolResult(false, "Task #" + taskId + " deleted.");
        }

        TaskStatus newStatus = null;
        if (statusStr != null && !statusStr.isBlank()) {
            try {
                newStatus = TaskStatus.fromString(statusStr);
            } catch (IllegalArgumentException e) {
                return new ToolResult(true, "Error: " + e.getMessage());
            }
        }

        List<String> addBlocks = parseCommaSeparated(input.get("addBlocks"));
        List<String> addBlockedBy = parseCommaSeparated(input.get("addBlockedBy"));

        try {
            Task updated = taskStore.updateTask(taskId, newStatus,
                    input.get("subject"), input.get("description"),
                    input.get("owner"), addBlocks, addBlockedBy);
            return new ToolResult(false, "Task #" + taskId + " updated.\n" + updated.toSummaryLine());
        } catch (IllegalArgumentException e) {
            return new ToolResult(true, "Error: " + e.getMessage());
        }
    }

    private List<String> parseCommaSeparated(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
