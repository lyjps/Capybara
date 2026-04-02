package com.co.claudecode.demo.tool.impl;

import com.co.claudecode.demo.task.Task;
import com.co.claudecode.demo.task.TaskStore;
import com.co.claudecode.demo.tool.Tool;
import com.co.claudecode.demo.tool.ToolExecutionContext;
import com.co.claudecode.demo.tool.ToolMetadata;
import com.co.claudecode.demo.tool.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 列出所有任务工具。
 * <p>
 * 对应 TS 原版 {@code TaskListTool}。
 */
public final class TaskListTool implements Tool {

    private final TaskStore taskStore;

    public TaskListTool(TaskStore taskStore) {
        this.taskStore = taskStore;
    }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                "task_list", "List all tasks in the task list",
                true, true, false,
                ToolMetadata.PathDomain.NONE, null,
                List.of());
    }

    @Override
    public ToolResult execute(Map<String, String> input, ToolExecutionContext context) {
        List<Task> tasks = taskStore.listTasks();
        if (tasks.isEmpty()) {
            return new ToolResult(false, "No tasks found.");
        }
        String summary = tasks.stream()
                .map(Task::toSummaryLine)
                .collect(Collectors.joining("\n"));
        return new ToolResult(false, summary);
    }
}
