package com.co.claudecode.demo.tool;

import java.util.Map;

/**
 * tool 的职责只聚焦在能力本身。
 * 权限与调度放在外层，是为了避免每个 tool 重复实现一套近似的治理逻辑。
 */
public interface Tool {

    ToolMetadata metadata();

    default void validate(Map<String, String> input) {
        // demo 保持最小接口，只有需要约束的工具才覆写。
    }

    ToolResult execute(Map<String, String> input, ToolExecutionContext context) throws Exception;
}
