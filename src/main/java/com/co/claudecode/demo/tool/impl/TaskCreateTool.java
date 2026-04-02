package com.co.claudecode.demo.tool.impl;

import com.co.claudecode.demo.task.TaskStore;
import com.co.claudecode.demo.tool.Tool;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolMetadata;
import com.co.claudecode.demo.tool.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * 创建任务工具。
 * <p>
 * 对应 TS 原版 {@code TaskCreateTool}。
 */
public final class TaskCreateTool implements Tool {

    private final TaskStore taskStore;

    public TaskCreateTool(TaskStore taskStore) {
        this.taskStore = taskStore;
    }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                "task_create", "Create a new task in the task list",
                false, true, false,
                ToolMetadata.PathDomain.NONE, null,
                List.of(
                        new ToolMetadata.ParamInfo("subject", "A brief title for the task", true),
                        new ToolMetadata.ParamInfo("description", "What needs to be done", true)
                ));
    }

    @Override
    public ToolResult execute(Map<String, String> input, ToolExecutionContext context) {
        String subject = input.get("subject");
        String description = input.getOrDefault("description", "");
        if (subject == null || subject.isBlank()) {
            return new ToolResult(true, "Error: subject is required");
        }
        String id = taskStore.createTask(subject, description);
        return new ToolResult(false, "Task #" + id + " created: " + subject);
    }
}
