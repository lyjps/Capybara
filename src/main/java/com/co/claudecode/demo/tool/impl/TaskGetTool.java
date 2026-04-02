package com.co.claudecode.demo.tool.impl;

import com.co.claudecode.demo.task.Task;
import com.co.claudecode.demo.task.TaskStore;
import com.co.claudecode.demo.tool.Tool;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolMetadata;
import com.co.claudecode.demo.tool.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * 获取任务详情工具。
 * <p>
 * 对应 TS 原版 {@code TaskGetTool}。
 */
public final class TaskGetTool implements Tool {

    private final TaskStore taskStore;

    public TaskGetTool(TaskStore taskStore) {
        this.taskStore = taskStore;
    }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                "task_get", "Get full details of a task by ID",
                true, true, false,
                ToolMetadata.PathDomain.NONE, null,
                List.of(
                        new ToolMetadata.ParamInfo("taskId", "The ID of the task to retrieve", true)
                ));
    }

    @Override
    public ToolResult execute(Map<String, String> input, ToolExecutionContext context) {
        String taskId = input.get("taskId");
        if (taskId == null || taskId.isBlank()) {
            return new ToolResult(true, "Error: taskId is required");
        }
        Task task = taskStore.getTask(taskId);
        if (task == null) {
            return new ToolResult(true, "Error: Task #" + taskId + " not found");
        }
        return new ToolResult(false, task.toDetail());
    }
}
